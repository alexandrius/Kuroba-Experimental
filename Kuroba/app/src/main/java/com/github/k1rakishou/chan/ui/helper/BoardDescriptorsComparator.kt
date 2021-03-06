package com.github.k1rakishou.chan.ui.helper

import com.github.k1rakishou.model.data.descriptor.BoardDescriptor
import kotlin.math.max

class BoardDescriptorsComparator<T>(
  private val query: String,
  private val boardDescriptorSelector: (T) -> BoardDescriptor,
) : Comparator<T> {

  override fun compare(o1: T, o2: T): Int {
    val boardCode1 = boardDescriptorSelector(o1).boardCode
    val boardCode2 = boardDescriptorSelector(o2).boardCode

    if (query.isEmpty()) {
      return boardCode1.compareTo(boardCode2)
    }

    val (maxOccurrenceLen1, position1) = queryOccurrenceLengthAndPosition(query, boardCode1)
    val (maxOccurrenceLen2, position2) = queryOccurrenceLengthAndPosition(query, boardCode2)

    if (position1 < position2) {
      return -1
    } else if (position1 > position2) {
      return 1
    }

    if (maxOccurrenceLen1 < maxOccurrenceLen2) {
      return -1
    } else if (maxOccurrenceLen1 > maxOccurrenceLen2) {
      return 1
    }

    if (boardCode1.length < boardCode2.length) {
      return -1
    } else if (boardCode1.length > boardCode2.length) {
      return 1
    }

    return boardCode1.compareTo(boardCode2)
  }

  private fun queryOccurrenceLengthAndPosition(query: String, boardCode: String): Pair<Int, Int> {
    require(query.length <= boardCode.length)

    var maxLen = 0
    var index = 0
    var position = 0

    while (index < boardCode.length) {
      var occurrenceLen = 0

      for (j in query.indices) {
        if (boardCode[index] != query[j]) {
          break
        }

        ++occurrenceLen
      }

      val newMaxLen = max(maxLen, occurrenceLen)
      if (newMaxLen > maxLen) {
        position = index
      }

      maxLen = newMaxLen
      ++index
    }

    return maxLen to position
  }
}