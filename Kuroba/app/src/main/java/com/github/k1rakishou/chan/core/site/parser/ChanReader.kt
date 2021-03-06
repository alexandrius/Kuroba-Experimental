/*
 * KurobaEx - *chan browser https://github.com/K1rakishou/Kuroba-Experimental/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.k1rakishou.chan.core.site.parser

import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.model.data.bookmark.ThreadBookmarkInfoObject
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.google.gson.stream.JsonReader

interface ChanReader {
  suspend fun getParser(): PostParser?

  @Throws(Exception::class)
  suspend fun loadThread(
    reader: JsonReader,
    chanReaderProcessor: ChanReaderProcessor
  )

  @Throws(Exception::class)
  suspend fun loadCatalog(reader: JsonReader, chanReaderProcessor: ChanReaderProcessor)

  suspend fun readThreadBookmarkInfoObject(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    expectedCapacity: Int,
    reader: JsonReader
  ): ModularResult<ThreadBookmarkInfoObject>

  companion object {
    const val DEFAULT_POST_LIST_CAPACITY = 16
  }
}