package com.github.k1rakishou.model.data.board

import com.github.k1rakishou.model.data.descriptor.BoardDescriptor

data class ChanBoard(
  val boardDescriptor: BoardDescriptor,
  var active: Boolean = false,
  val order: Int = 0,
  val name: String? = null,
  val perPage: Int = 15,
  val pages: Int = 10,
  val maxFileSize: Int = -1,
  val maxWebmSize: Int = -1,
  val maxCommentChars: Int = -1,
  val bumpLimit: Int = -1,
  val imageLimit: Int = -1,
  val cooldownThreads: Int = 0,
  val cooldownReplies: Int = 0,
  val cooldownImages: Int = 0,
  val customSpoilers: Int = -1,
  val description: String = "",
  val workSafe: Boolean = false,
  val spoilers: Boolean = false,
  val userIds: Boolean = false,
  val codeTags: Boolean = false,
  val preuploadCaptcha: Boolean = false,
  val countryFlags: Boolean = false,
  val mathTags: Boolean = false,
  @Deprecated("delete me")
  val archive: Boolean = false
) {

  fun boardName(): String = name ?: "No board name"
  fun siteName(): String =  boardDescriptor.siteName()
  fun boardCode(): String = boardDescriptor.boardCode

  companion object {
    const val DEFAULT_CATALOG_SIZE = 150

    @JvmStatic
    fun create(boardDescriptor: BoardDescriptor, boardName: String): ChanBoard {
      return ChanBoard(
        boardDescriptor = boardDescriptor,
        name = boardName
      )
    }
  }

}