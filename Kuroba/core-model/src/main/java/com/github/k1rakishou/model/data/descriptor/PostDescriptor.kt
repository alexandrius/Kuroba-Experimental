package com.github.k1rakishou.model.data.descriptor

open class PostDescriptor protected constructor(
  /**
   * A post may belong to a thread or to a catalog (OP) that's why we use abstract
   * ChanDescriptor here and not a concrete Thread/Catalog descriptor
   * */
  val descriptor: ChanDescriptor,
  val postNo: Long,
  open val postSubNo: Long = 0L
) {

  fun isOP(): Boolean {
    return when (descriptor) {
      is ChanDescriptor.ThreadDescriptor -> postNo == descriptor.threadNoOrNull()
      is ChanDescriptor.CatalogDescriptor -> true
    }
  }

  fun threadDescriptor(): ChanDescriptor.ThreadDescriptor {
    return when (descriptor) {
      is ChanDescriptor.ThreadDescriptor -> descriptor
      is ChanDescriptor.CatalogDescriptor -> descriptor.toThreadDescriptor(postNo)
    }
  }

  fun catalogDescriptor(): ChanDescriptor.CatalogDescriptor {
    return when (val desc = descriptor) {
      is ChanDescriptor.ThreadDescriptor -> desc.catalogDescriptor()
      is ChanDescriptor.CatalogDescriptor -> desc
    }
  }

  fun boardDescriptor(): BoardDescriptor = descriptor.boardDescriptor()
  fun siteDescriptor(): SiteDescriptor = descriptor.siteDescriptor()

  fun getThreadNo(): Long {
    return when (descriptor) {
      is ChanDescriptor.ThreadDescriptor -> descriptor.threadNo
      is ChanDescriptor.CatalogDescriptor -> {
        require(postNo > 0) { "Bad postNo: $postNo" }
        postNo
      }
    }
  }

  fun serializeToString(): String {
    return "PD_${descriptor.serializeToString()}_${postNo}_${postSubNo}"
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is PostDescriptor) return false

    if (descriptor != other.descriptor) return false
    if (postNo != other.postNo) return false
    if (postSubNo != other.postSubNo) return false

    return true
  }

  override fun hashCode(): Int {
    var result = descriptor.hashCode()
    result = 31 * result + postNo.hashCode()
    result = 31 * result + postSubNo.hashCode()
    return result
  }

  override fun toString(): String {
    val threadNo = if (descriptor is ChanDescriptor.ThreadDescriptor) {
      descriptor.threadNo.toString()
    } else {
      postNo.toString()
    }

    return "PD(${descriptor.siteName()}/${descriptor.boardCode()}/$threadNo/$postNo,$postSubNo)"
  }

  companion object {

    @JvmStatic
    fun create(chanDescriptor: ChanDescriptor, postNo: Long): PostDescriptor {
      return when (chanDescriptor) {
        is ChanDescriptor.ThreadDescriptor -> create(
          chanDescriptor.siteName(),
          chanDescriptor.boardCode(),
          chanDescriptor.threadNo,
          postNo
        )
        is ChanDescriptor.CatalogDescriptor -> create(
          chanDescriptor.siteName(),
          chanDescriptor.boardCode(),
          postNo
        )
      }
    }

    @JvmStatic
    fun create(siteName: String, boardCode: String, threadNo: Long): PostDescriptor {
      require(threadNo > 0) { "Bad threadNo: $threadNo" }

      return PostDescriptor(
        ChanDescriptor.CatalogDescriptor.create(siteName, boardCode),
        threadNo
      )
    }

    @JvmStatic
    fun create(boardDescriptor: BoardDescriptor, threadNo: Long, postNo: Long, postSubNo: Long = 0L): PostDescriptor {
      return create(boardDescriptor.siteName(), boardDescriptor.boardCode, threadNo, postNo, postSubNo)
    }

    @JvmStatic
    fun create(chanDescriptor: ChanDescriptor, threadNo: Long, postNo: Long, postSubNo: Long = 0L): PostDescriptor {
      return create(chanDescriptor.siteName(), chanDescriptor.boardCode(), threadNo, postNo, postSubNo)
    }

    @JvmOverloads
    @JvmStatic
    fun create(siteName: String, boardCode: String, threadNo: Long, postNo: Long, postSubNo: Long = 0L): PostDescriptor {
      require(threadNo > 0) { "Bad threadNo: $threadNo" }
      require(postNo > 0) { "Bad postNo: $postNo" }

      return PostDescriptor(
        ChanDescriptor.ThreadDescriptor.create(siteName, boardCode, threadNo),
        postNo,
        postSubNo
      )
    }
  }

}