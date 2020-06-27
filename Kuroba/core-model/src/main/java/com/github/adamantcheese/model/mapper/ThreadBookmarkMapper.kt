package com.github.adamantcheese.model.mapper

import com.github.adamantcheese.model.data.bookmark.ThreadBookmark
import com.github.adamantcheese.model.data.descriptor.ChanDescriptor
import com.github.adamantcheese.model.entity.bookmark.ThreadBookmarkEntity
import com.github.adamantcheese.model.entity.bookmark.ThreadBookmarkFull

object ThreadBookmarkMapper {

  fun toThreadBookmarkEntity(threadBookmark: ThreadBookmark, ownerThreadId: Long, order: Int): ThreadBookmarkEntity {
    require(ownerThreadId != 0L) { "Database id cannot be 0" }

    return ThreadBookmarkEntity(
      ownerThreadId = ownerThreadId,
      seenPostsCount = threadBookmark.seenPostsCount,
      totalPostsCount = threadBookmark.totalPostsCount,
      lastLoadedPostNo = threadBookmark.lastLoadedPostNo,
      lastViewedPostNo = threadBookmark.lastViewedPostNo,
      seenQuotesCount = 0,
      totalQuotesCount = 0,
      title = threadBookmark.title,
      thumbnailUrl = threadBookmark.thumbnailUrl,
      state = threadBookmark.state,
      bookmarkOrder = order
    )
  }

  fun toThreadBookmark(threadBookmarkFull: ThreadBookmarkFull): ThreadBookmark {
    return ThreadBookmark.create(
      threadDescriptor = ChanDescriptor.ThreadDescriptor.create(
        siteName = threadBookmarkFull.siteName,
        boardCode = threadBookmarkFull.boardCode,
        threadNo = threadBookmarkFull.threadNo
      )
    ).apply {
      this.seenPostsCount = threadBookmarkFull.threadBookmarkEntity.seenPostsCount
      this.totalPostsCount = threadBookmarkFull.threadBookmarkEntity.totalPostsCount
      this.lastLoadedPostNo = threadBookmarkFull.threadBookmarkEntity.lastLoadedPostNo
      this.lastViewedPostNo = threadBookmarkFull.threadBookmarkEntity.lastViewedPostNo
      this.title = threadBookmarkFull.threadBookmarkEntity.title
      this.thumbnailUrl = threadBookmarkFull.threadBookmarkEntity.thumbnailUrl
      this.state = threadBookmarkFull.threadBookmarkEntity.state
    }
  }

}