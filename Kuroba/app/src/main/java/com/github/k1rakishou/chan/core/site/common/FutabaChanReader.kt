package com.github.k1rakishou.chan.core.site.common

import com.github.k1rakishou.chan.core.manager.ArchivesManager
import com.github.k1rakishou.chan.core.manager.BoardManager
import com.github.k1rakishou.chan.core.manager.PostFilterManager
import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.chan.core.site.SiteEndpoints
import com.github.k1rakishou.chan.core.site.parser.ChanReader
import com.github.k1rakishou.chan.core.site.parser.ChanReader.Companion.DEFAULT_POST_LIST_CAPACITY
import com.github.k1rakishou.chan.core.site.parser.ChanReaderProcessor
import com.github.k1rakishou.chan.core.site.parser.CommentParser
import com.github.k1rakishou.chan.core.site.parser.MockReplyManager
import com.github.k1rakishou.chan.core.site.parser.PostParser
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.board.ChanBoard
import com.github.k1rakishou.model.data.bookmark.StickyThread
import com.github.k1rakishou.model.data.bookmark.ThreadBookmarkInfoObject
import com.github.k1rakishou.model.data.bookmark.ThreadBookmarkInfoPostObject
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.post.ChanPostBuilder
import com.github.k1rakishou.model.data.post.ChanPostHttpIcon
import com.github.k1rakishou.model.data.post.ChanPostImage
import com.github.k1rakishou.model.data.post.ChanPostImageBuilder
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.parser.Parser
import java.io.IOException
import kotlin.math.max

@Suppress("BlockingMethodInNonBlockingContext")
class FutabaChanReader(
  private val archivesManager: ArchivesManager,
  private val postFilterManager: PostFilterManager,
  private val mockReplyManager: MockReplyManager,
  private val siteManager: SiteManager,
  private val boardManager: BoardManager
) : ChanReader {
  private val mutex = Mutex()
  private var parser: PostParser? = null

  override suspend fun getParser(): PostParser {
    return mutex.withLock {
      if (parser == null) {
        val commentParser = CommentParser(mockReplyManager)
          .addDefaultRules()

        val defaultPostParser = DefaultPostParser(
          commentParser,
          postFilterManager,
          archivesManager
        )

        parser = defaultPostParser
      }

      return@withLock parser!!
    }
  }

  @Throws(Exception::class)
  override suspend fun loadThread(
    reader: JsonReader,
    chanReaderProcessor: ChanReaderProcessor
  ) {
    iteratePostsInThread(reader) { reader ->
      readPostObject(reader, chanReaderProcessor)
    }

    chanReaderProcessor.applyChanReadOptions()
  }

  @Throws(Exception::class)
  override suspend fun loadCatalog(reader: JsonReader, chanReaderProcessor: ChanReaderProcessor) {
    iterateThreadsInCatalog(reader) { reader ->
      readPostObject(reader, chanReaderProcessor)
    }
  }

  @Throws(Exception::class)
  private suspend fun readPostObject(reader: JsonReader, chanReaderProcessor: ChanReaderProcessor) {
    val builder = ChanPostBuilder()
    builder.boardDescriptor(chanReaderProcessor.chanDescriptor.boardDescriptor())

    val site = siteManager.bySiteDescriptor(chanReaderProcessor.chanDescriptor.siteDescriptor())
      ?: return
    val board = boardManager.byBoardDescriptor(chanReaderProcessor.chanDescriptor.boardDescriptor())
      ?: return

    val endpoints = site.endpoints()

    // File
    var fileId: String? = null
    var fileExt: String? = null
    var fileWidth = 0
    var fileHeight = 0
    var fileSize: Long = 0
    var fileSpoiler = false
    var fileName: String? = null
    var fileHash: String? = null
    var fileDeleted = false
    val files: MutableList<ChanPostImage> = ArrayList()

    // Country flag
    var countryCode: String? = null
    var trollCountryCode: String? = null
    var countryName: String? = null

    // 4chan pass leaf
    var since4pass = 0

    // A sticky thread may as well be a rolling sticky thread. The difference is that in rolling
    // sticky thread old posts will be deleted right away once they exceed the "sticky_cap" value.
    var stickyCap = -1

    reader.beginObject()

    while (reader.hasNext()) {
      when (reader.nextName()) {
        "no" -> builder.id(reader.nextInt().toLong())
        "sub" -> builder.subject(reader.nextString())
        "name" -> builder.name(reader.nextString())
        "com" -> builder.comment(reader.nextString())
        "tim" -> fileId = reader.nextString()
        "time" -> builder.setUnixTimestampSeconds(reader.nextLong())
        "ext" -> fileExt = reader.nextString().replace(".", "")
        "w" -> fileWidth = reader.nextInt()
        "h" -> fileHeight = reader.nextInt()
        "fsize" -> fileSize = reader.nextLong()
        "filename" -> fileName = reader.nextString()
        "trip" -> builder.tripcode(reader.nextString())
        "country" -> countryCode = reader.nextString()
        "troll_country" -> trollCountryCode = reader.nextString()
        "country_name" -> countryName = reader.nextString()
        "spoiler" -> fileSpoiler = reader.nextInt() == 1
        "resto" -> {
          val opId = reader.nextInt()
          builder.op(opId == 0)
          builder.opId(opId.toLong())
        }
        "filedeleted" -> fileDeleted = reader.nextInt() == 1
        "sticky" -> builder.sticky(reader.nextInt() == 1)
        "closed" -> builder.closed(reader.nextInt() == 1)
        "archived" -> builder.archived(reader.nextInt() == 1)
        "replies" -> builder.replies(reader.nextInt())
        "images" -> builder.threadImagesCount(reader.nextInt())
        "unique_ips" -> builder.uniqueIps(reader.nextInt())
        "last_modified" -> builder.lastModified(reader.nextLong())
        "id" -> builder.posterId(reader.nextString())
        "capcode" -> builder.moderatorCapcode(reader.nextString())
        "since4pass" -> since4pass = reader.nextInt()
        "extra_files" -> {
          reader.beginArray()

          while (reader.hasNext()) {
            val postImage = readPostImage(reader, builder, board, endpoints)
            if (postImage != null) {
              files.add(postImage)
            }
          }

          reader.endArray()
        }
        "md5" -> fileHash = reader.nextString()
        "sticky_cap" -> stickyCap = reader.nextInt()
        else -> {
          // Unknown/ignored key
          reader.skipValue()
        }
      }
    }

    reader.endObject()

    if (!builder.hasPostDescriptor()) {
      Logger.e(TAG, "readPostObject() Post has no PostDescriptor!")
      return
    }

    // The file from between the other values.
    if (fileId != null && fileName != null && fileExt != null && !fileDeleted) {
      val args = SiteEndpoints.makeArgument("tim", fileId, "ext", fileExt)
      val image = ChanPostImageBuilder()
        .serverFilename(fileId)
        .thumbnailUrl(endpoints.thumbnailUrl(builder, false, board.customSpoilers, args))
        .spoilerThumbnailUrl(endpoints.thumbnailUrl(builder, true, board.customSpoilers, args))
        .imageUrl(endpoints.imageUrl(builder, args))
        .filename(Parser.unescapeEntities(fileName, false))
        .extension(fileExt)
        .imageWidth(fileWidth)
        .imageHeight(fileHeight)
        .spoiler(fileSpoiler)
        .size(fileSize)
        .fileHash(fileHash, true)
        .build()

      // Insert it at the beginning.
      files.add(0, image)
    }

    builder.postImages(files, builder.postDescriptor)

    if (builder.op) {
      // Update OP fields later on the main thread
      val op = ChanPostBuilder()
      op.closed(builder.closed)
      op.archived(builder.archived)
      op.sticky(builder.sticky)
      op.replies(builder.totalRepliesCount)
      op.threadImagesCount(builder.threadImagesCount)
      op.uniqueIps(builder.uniqueIps)
      op.lastModified(builder.lastModified)

      if (builder.sticky) {
        op.stickyCap(stickyCap)
      }

      chanReaderProcessor.setOp(op)
    }

    if (countryCode != null && countryName != null) {
      val countryUrl = endpoints.icon("country", SiteEndpoints.makeArgument("country_code", countryCode))
      builder.addHttpIcon(ChanPostHttpIcon(countryUrl, "$countryName/$countryCode"))
    }

    if (trollCountryCode != null && countryName != null) {
      val countryUrl = endpoints.icon("troll_country", SiteEndpoints.makeArgument("troll_country_code", trollCountryCode))
      builder.addHttpIcon(ChanPostHttpIcon(countryUrl, "$countryName/t_$trollCountryCode"))
    }

    if (since4pass != 0) {
      val iconUrl = endpoints.icon("since4pass", null)
      builder.addHttpIcon(ChanPostHttpIcon(iconUrl, since4pass.toString()))
    }

    chanReaderProcessor.addPost(builder)
  }

  @Throws(IOException::class)
  private fun readPostImage(
    reader: JsonReader,
    builder: ChanPostBuilder,
    board: ChanBoard,
    endpoints: SiteEndpoints
  ): ChanPostImage? {
    reader.beginObject()

    var fileId: String? = null
    var fileSize: Long = 0
    var fileExt: String? = null
    var fileWidth = 0
    var fileHeight = 0
    var fileSpoiler = false
    var fileName: String? = null
    var fileHash: String? = null

    while (reader.hasNext()) {
      when (reader.nextName()) {
        "tim" -> fileId = reader.nextString()
        "fsize" -> fileSize = reader.nextLong()
        "w" -> fileWidth = reader.nextInt()
        "h" -> fileHeight = reader.nextInt()
        "spoiler" -> fileSpoiler = reader.nextInt() == 1
        "ext" -> fileExt = reader.nextString().replace(".", "")
        "filename" -> fileName = reader.nextString()
        "md5" -> fileHash = reader.nextString()
        else -> reader.skipValue()
      }
    }

    reader.endObject()

    if (fileId != null && fileName != null && fileExt != null) {
      val args = SiteEndpoints.makeArgument("tim", fileId, "ext", fileExt)
      return ChanPostImageBuilder()
        .serverFilename(fileId)
        .thumbnailUrl(endpoints.thumbnailUrl(builder, false, board.customSpoilers, args))
        .spoilerThumbnailUrl(endpoints.thumbnailUrl(builder, true, board.customSpoilers, args))
        .imageUrl(endpoints.imageUrl(builder, args))
        .filename(Parser.unescapeEntities(fileName, false))
        .extension(fileExt)
        .imageWidth(fileWidth)
        .imageHeight(fileHeight)
        .spoiler(fileSpoiler)
        .size(fileSize)
        .fileHash(fileHash, true)
        .build()
    }

    return null
  }

  override suspend fun readThreadBookmarkInfoObject(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    expectedCapacity: Int,
    reader: JsonReader
  ): ModularResult<ThreadBookmarkInfoObject> {
    return ModularResult.Try {
      val postObjects = ArrayList<ThreadBookmarkInfoPostObject>(
        max(expectedCapacity, DEFAULT_POST_LIST_CAPACITY)
      )

      iteratePostsInThread(reader) { reader ->
        val postObject = readThreadBookmarkInfoPostObject(reader)
        if (postObject != null) {
          postObjects += postObject
        }
      }

      val originalPost = postObjects.firstOrNull { postObject ->
        postObject is ThreadBookmarkInfoPostObject.OriginalPost
      } as? ThreadBookmarkInfoPostObject.OriginalPost
        ?: throw IllegalStateException("Thread $threadDescriptor has no OP")

      check(threadDescriptor.threadNo == originalPost.postNo) {
        "Original post has incorrect postNo, " +
          "expected: ${threadDescriptor.threadNo}, actual: ${originalPost.postNo}"
      }

      return@Try ThreadBookmarkInfoObject(threadDescriptor, postObjects)
    }
  }

  @Throws(Exception::class)
  private suspend fun readThreadBookmarkInfoPostObject(reader: JsonReader): ThreadBookmarkInfoPostObject? {
    var isOp: Boolean = false
    var postNo: Long? = null
    var closed: Boolean = false
    var archived: Boolean = false
    var bumpLimit: Boolean = false
    var imageLimit: Boolean = false
    var comment: String = ""
    var sticky: Boolean = false
    var stickyCap: Int = -1

    reader.beginObject()

    while (reader.hasNext()) {
      when (reader.nextName()) {
        "no" -> postNo = reader.nextInt().toLong()
        "closed" -> closed = reader.nextInt() == 1
        "archived" -> archived = reader.nextInt() == 1
        "com" -> comment = reader.nextString()
        "resto" -> {
          val opId = reader.nextInt()
          isOp = opId == 0
        }
        "bumplimit" -> bumpLimit = reader.nextInt() == 1
        "imagelimit" -> imageLimit = reader.nextInt() == 1
        "sticky" -> sticky = reader.nextInt() == 1
        "sticky_cap" -> stickyCap = reader.nextInt()
        else -> {
          // Unknown/ignored key
          reader.skipValue()
        }
      }
    }

    reader.endObject()

    if (isOp) {
      if (postNo == null) {
        Logger.e(TAG, "Error reading OriginalPost (postNo=$postNo)")
        return null
      }

      val stickyPost = StickyThread.create(sticky, stickyCap)

      return ThreadBookmarkInfoPostObject.OriginalPost(
        postNo,
        closed,
        archived,
        bumpLimit,
        imageLimit,
        stickyPost,
        comment
      )
    } else {
      if (postNo == null) {
        Logger.e(TAG, "Error reading RegularPost (isOp=$isOp)")
        return null
      }

      return ThreadBookmarkInfoPostObject.RegularPost(postNo, comment)
    }
  }

  private suspend fun iteratePostsInThread(reader: JsonReader, iterator: suspend (JsonReader) -> Unit) {
    reader.beginObject()

    // Page object
    while (reader.hasNext()) {
      val key = reader.nextName()
      if (key == "posts") {
        reader.beginArray()

        // Thread array
        while (reader.hasNext()) {
          // Thread object
          iterator(reader)
        }

        reader.endArray()
      } else {
        reader.skipValue()
      }
    }

    reader.endObject()
  }

  private suspend fun iterateThreadsInCatalog(reader: JsonReader, iterator: suspend (JsonReader) -> Unit) {
    reader.beginArray() // Array of pages

    while (reader.hasNext()) {
      reader.beginObject() // Page object

      while (reader.hasNext()) {
        if (reader.nextName() == "threads") {
          reader.beginArray() // Threads array

          while (reader.hasNext()) {
            iterator(reader)
          }

          reader.endArray()
        } else {
          reader.skipValue()
        }
      }

      reader.endObject()
    }

    reader.endArray()
  }

  companion object {
    private const val TAG = "FutabaChanReader"
  }
}