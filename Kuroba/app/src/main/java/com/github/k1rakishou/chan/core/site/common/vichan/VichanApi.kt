package com.github.k1rakishou.chan.core.site.common.vichan

import com.github.k1rakishou.chan.core.manager.BoardManager
import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.chan.core.site.SiteEndpoints
import com.github.k1rakishou.chan.core.site.common.CommonSite
import com.github.k1rakishou.chan.core.site.common.CommonSite.CommonApi
import com.github.k1rakishou.chan.core.site.parser.ChanReader
import com.github.k1rakishou.chan.core.site.parser.ChanReaderProcessor
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.common.isNotNullNorEmpty
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.board.ChanBoard
import com.github.k1rakishou.model.data.bookmark.ThreadBookmarkInfoObject
import com.github.k1rakishou.model.data.bookmark.ThreadBookmarkInfoPostObject
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.post.ChanPostBuilder
import com.github.k1rakishou.model.data.post.ChanPostHttpIcon
import com.github.k1rakishou.model.data.post.ChanPostImage
import com.github.k1rakishou.model.data.post.ChanPostImageBuilder
import com.google.gson.stream.JsonReader
import org.jsoup.parser.Parser
import java.io.IOException
import java.util.*
import kotlin.math.max

@Suppress("BlockingMethodInNonBlockingContext")
class VichanApi(
  private val siteManager: SiteManager,
  private val boardManager: BoardManager,
  commonSite: CommonSite
) : CommonApi(commonSite) {

  @Throws(Exception::class)
  override suspend fun loadThread(
    reader: JsonReader,
    chanReaderProcessor: ChanReaderProcessor
  ) {
    vichanReaderExtensions.iteratePostsInThread(reader) { reader ->
      readPostObject(reader, chanReaderProcessor)
    }

    chanReaderProcessor.applyChanReadOptions()
  }

  @Throws(Exception::class)
  override suspend fun loadCatalog(reader: JsonReader, chanReaderProcessor: ChanReaderProcessor) {
    vichanReaderExtensions.iterateThreadsInCatalog(reader) { reader ->
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
    val files: MutableList<ChanPostImage> = ArrayList()

    // Country flag
    var countryCode: String? = null
    var trollCountryCode: String? = null
    var countryName: String? = null

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
        "sticky" -> builder.sticky(reader.nextInt() == 1)
        "closed" -> builder.closed(reader.nextInt() == 1)
        "archived" -> builder.archived(reader.nextInt() == 1)
        "replies" -> builder.replies(reader.nextInt())
        "images" -> builder.threadImagesCount(reader.nextInt())
        "unique_ips" -> builder.uniqueIps(reader.nextInt())
        "last_modified" -> builder.lastModified(reader.nextLong())
        "id" -> builder.posterId(reader.nextString())
        "capcode" -> builder.moderatorCapcode(reader.nextString())
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
    if (!fileId.isNullOrEmpty() && !fileExt.isNullOrEmpty()) {
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

    chanReaderProcessor.addPost(builder)
  }

  @Throws(IOException::class)
  private fun readPostImage(
    reader: JsonReader,
    builder: ChanPostBuilder,
    board: ChanBoard,
    endpoints: SiteEndpoints
  ): ChanPostImage? {
    try {
      reader.beginObject()
    } catch (e: Exception) {
      //workaround for weird 8chan error where extra_files has a random empty array in it
      reader.beginArray()
      reader.endArray()
      try {
        reader.beginObject()
      } catch (e1: Exception) {
        return null
      }
    }

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

    if (fileId.isNotNullNorEmpty() && fileName.isNotNullNorEmpty() && fileExt.isNotNullNorEmpty()) {
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
        max(expectedCapacity, ChanReader.DEFAULT_POST_LIST_CAPACITY)
      )

      vichanReaderExtensions.iteratePostsInThread(reader) { reader ->
        val postObject = vichanReaderExtensions.readThreadBookmarkInfoPostObject(reader)
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

  companion object {
    private const val TAG = "VichanApi"
  }

}