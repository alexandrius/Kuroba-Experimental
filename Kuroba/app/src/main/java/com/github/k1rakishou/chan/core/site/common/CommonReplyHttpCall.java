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
package com.github.k1rakishou.chan.core.site.common;

import androidx.annotation.Nullable;

import com.github.k1rakishou.chan.core.site.Site;
import com.github.k1rakishou.chan.core.site.http.HttpCall;
import com.github.k1rakishou.chan.core.site.http.ProgressRequestBody;
import com.github.k1rakishou.chan.core.site.http.ReplyResponse;
import com.github.k1rakishou.core_logger.Logger;
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor;

import org.jsoup.Jsoup;

import java.io.IOException;
import java.util.Objects;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.Response;

public abstract class CommonReplyHttpCall extends HttpCall {
    private static final String TAG = "CommonReplyHttpCall";
    private static final Random RANDOM = new Random();
    private static final Pattern THREAD_NO_PATTERN = Pattern.compile("<!-- thread:([0-9]+),no:([0-9]+) -->");
    private static final Pattern ERROR_MESSAGE = Pattern.compile("\"errmsg\"[^>]*>(.*?)</span");
    private static final String PROBABLY_BANNED_TEXT = "banned";
    private static final String PROBABLY_IP_BLOCKED = "Posting from your IP range has been blocked due to abuse";

    public final ChanDescriptor replyChanDescriptor;
    public final ReplyResponse replyResponse = new ReplyResponse();

    public CommonReplyHttpCall(Site site, ChanDescriptor replyChanDescriptor) {
        super(site);

        ChanDescriptor chanDescriptor = Objects.requireNonNull(
                replyChanDescriptor,
                "reply.chanDescriptor == null"
        );

        this.replyChanDescriptor = replyChanDescriptor;
        this.replyResponse.siteDescriptor = chanDescriptor.siteDescriptor();
        this.replyResponse.boardCode = chanDescriptor.boardCode();
    }

    @Override
    public void setup(
            Request.Builder requestBuilder,
            @Nullable ProgressRequestBody.ProgressRequestListener progressListener
    ) throws IOException {
        replyResponse.password = Long.toHexString(RANDOM.nextLong());

        MultipartBody.Builder formBuilder = new MultipartBody.Builder();
        formBuilder.setType(MultipartBody.FORM);
        addParameters(formBuilder, progressListener);

        HttpUrl replyUrl = getSite().endpoints().reply(this.replyChanDescriptor);
        requestBuilder.url(replyUrl);
        requestBuilder.addHeader("Referer", replyUrl.toString());

        modifyRequestBuilder(requestBuilder);
        requestBuilder.post(formBuilder.build());
    }

    @Override
    public void process(Response response, String result) {
        Matcher errorMessageMatcher = ERROR_MESSAGE.matcher(result);
        if (errorMessageMatcher.find()) {
            replyResponse.errorMessage = Jsoup.parse(errorMessageMatcher.group(1)).body().text();
            replyResponse.probablyBanned = checkIfBanned();
            return;
        }

        Matcher threadNoMatcher = THREAD_NO_PATTERN.matcher(result);
        if (!threadNoMatcher.find()) {
            Logger.e(TAG, "Couldn't handle server response! response = \"" + result + "\"");
            return;
        }

        try {
            replyResponse.threadNo = Integer.parseInt(threadNoMatcher.group(1));
            replyResponse.postNo = Integer.parseInt(threadNoMatcher.group(2));

            if (replyResponse.threadNo == 0) {
                replyResponse.threadNo = replyResponse.postNo;
            }
        } catch (NumberFormatException error) {
            Logger.e(TAG, "ReplyResponse parsing error", error);
        }

        if (replyResponse.threadNo > 0 && replyResponse.postNo > 0) {
            replyResponse.posted = true;
            return;
        }

        Logger.e(TAG, "Couldn't handle server response! response = \"" + result + "\"");
    }

    private boolean checkIfBanned() {
        boolean isBannedFound = replyResponse.errorMessage.contains(PROBABLY_BANNED_TEXT);
        if (isBannedFound) {
            return true;
        }

        if (!replyChanDescriptor.siteDescriptor().is4chan()) {
            return false;
        }

        return replyResponse.errorMessage.contains(PROBABLY_IP_BLOCKED);
    }

    public abstract void addParameters(
            MultipartBody.Builder builder,
            @Nullable ProgressRequestBody.ProgressRequestListener progressListener
    ) throws IOException;

    protected void modifyRequestBuilder(Request.Builder requestBuilder) {

    }
}
