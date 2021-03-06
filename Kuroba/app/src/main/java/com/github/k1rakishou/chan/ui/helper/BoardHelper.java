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
package com.github.k1rakishou.chan.ui.helper;

import com.github.k1rakishou.model.data.board.ChanBoard;

import org.jsoup.parser.Parser;

public class BoardHelper {
    private static final String TAG = "BoardHelper";

    public static String getName(ChanBoard board) {
        return getName(board.boardCode(), board.getName());
    }

    public static String getName(String boardCode, String boardName) {
        return "/" + boardCode + "/ \u2013 " + boardName;
    }

    public static String getDescription(ChanBoard board) {
        return Parser.unescapeEntities(board.getDescription(), false);
    }

    public static String boardUniqueId(ChanBoard board) {
        String code = board.boardCode().replace(":", "").replace(",", "");
        return board.getBoardDescriptor().siteName() + ":" + code;
    }

    public static boolean matchesUniqueId(ChanBoard board, String uniqueId) {
        if (!uniqueId.contains(":")) {
            return board.getBoardDescriptor().getSiteDescriptor().is4chan() && board.boardCode().equals(uniqueId);
        }

        String[] splitted = uniqueId.split(":");
        if (splitted.length != 2) {
            return false;
        }

        try {
            return splitted[0].equals(board.getBoardDescriptor().siteName()) && splitted[1].equals(board.boardCode());
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
