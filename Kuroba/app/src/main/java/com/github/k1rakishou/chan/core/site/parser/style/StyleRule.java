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
package com.github.k1rakishou.chan.core.site.parser.style;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.k1rakishou.chan.core.site.parser.CommentParserHelper;
import com.github.k1rakishou.chan.core.site.parser.PostParser;
import com.github.k1rakishou.core_spannable.AbsoluteSizeSpanHashed;
import com.github.k1rakishou.core_spannable.ColorizableBackgroundColorSpan;
import com.github.k1rakishou.core_spannable.ColorizableForegroundColorSpan;
import com.github.k1rakishou.core_spannable.CustomTypefaceSpan;
import com.github.k1rakishou.core_spannable.PostLinkable;
import com.github.k1rakishou.core_themes.ChanThemeColorId;
import com.github.k1rakishou.model.data.post.ChanPostBuilder;

import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StyleRule {
    private final List<String> blockElements = Arrays.asList("p", "div");

    private String tag;
    private List<String> classes;
    private List<Action> actions = new ArrayList<>();
    private ChanThemeColorId foregroundChanThemeColorId = null;
    private ChanThemeColorId backgroundChanThemeColorId = null;
    private boolean strikeThrough;
    private boolean underline;
    private boolean bold;
    private boolean italic;
    private boolean monospace;
    private Typeface typeface;
    private int size = 0;
    private PostLinkable.Type link = null;
    private boolean nullify;
    private boolean linkify;
    private String justText = null;
    private boolean blockElement;

    public static StyleRule tagRule(String tag) {
        return new StyleRule().tag(tag);
    }

    public StyleRule tag(String tag) {
        this.tag = tag;

        if (blockElements.contains(tag)) {
            blockElement = true;
        }

        return this;
    }

    public String tag() {
        return tag;
    }

    public StyleRule cssClass(String cssClass) {
        if (classes == null) {
            classes = new ArrayList<>(4);
        }
        classes.add(cssClass);

        return this;
    }

    public StyleRule action(Action action) {
        actions.add(action);
        return this;
    }

    public StyleRule foregroundColorId(ChanThemeColorId foregroundChanThemeColorId) {
        this.foregroundChanThemeColorId = foregroundChanThemeColorId;
        return this;
    }

    public StyleRule backgroundColorId(ChanThemeColorId backgroundChanThemeColorId) {
        this.backgroundChanThemeColorId = backgroundChanThemeColorId;
        return this;
    }

    public StyleRule link(PostLinkable.Type link) {
        this.link = link;
        return this;
    }

    public StyleRule strikeThrough() {
        strikeThrough = true;
        return this;
    }

    public StyleRule underline() {
        this.underline = true;
        return this;
    }

    public StyleRule bold() {
        bold = true;
        return this;
    }

    public StyleRule italic() {
        italic = true;
        return this;
    }

    public StyleRule monospace() {
        monospace = true;
        return this;
    }

    public StyleRule typeface(Typeface typeface) {
        this.typeface = typeface;
        return this;
    }

    public StyleRule size(int size) {
        this.size = size;
        return this;
    }

    public StyleRule nullify() {
        nullify = true;
        return this;
    }

    public StyleRule linkify() {
        linkify = true;
        return this;
    }

    public StyleRule just(String justText) {
        this.justText = justText;
        return this;
    }

    public boolean highPriority() {
        return classes != null && !classes.isEmpty();
    }

    public boolean applies(Element element) {
        if (classes == null || classes.isEmpty()) {
            return true;
        }

        for (String c : classes) {
            if (element.hasClass(c)) {
                return true;
            }
        }

        return false;
    }

    public CharSequence apply(StyleRulesParams styleRulesParams) {
        if (nullify) {
            return null;
        }

        if (justText != null) {
            return justText;
        }

        @NonNull CharSequence resultText = styleRulesParams.getText();
        @NonNull Element element = styleRulesParams.getElement();

        @Nullable ChanPostBuilder post = styleRulesParams.getPost();
        @Nullable PostParser.Callback callback = styleRulesParams.getCallback();

        if (callback != null && post != null) {
            for (Action action : actions) {
                resultText = action.execute(callback, post, resultText, element);
            }
        }

        List<Object> spansToApply = new ArrayList<>(2);

        if (backgroundChanThemeColorId != null) {
            spansToApply.add(new ColorizableBackgroundColorSpan(backgroundChanThemeColorId));
        }

        if (foregroundChanThemeColorId != null) {
            spansToApply.add(new ColorizableForegroundColorSpan(foregroundChanThemeColorId));
        }

        if (strikeThrough) {
            spansToApply.add(new StrikethroughSpan());
        }

        if (underline) {
            spansToApply.add(new UnderlineSpan());
        }

        if (bold && italic) {
            spansToApply.add(new StyleSpan(Typeface.BOLD_ITALIC));
        } else if (bold) {
            spansToApply.add(new StyleSpan(Typeface.BOLD));
        } else if (italic) {
            spansToApply.add(new StyleSpan(Typeface.ITALIC));
        }

        if (monospace) {
            spansToApply.add(new TypefaceSpan("monospace"));
        }

        if (typeface != null) {
            spansToApply.add(new CustomTypefaceSpan("", typeface));
        }

        if (size != 0) {
            spansToApply.add(new AbsoluteSizeSpanHashed(size));
        }

        if (link != null && post != null) {
            PostLinkable pl = new PostLinkable(
                    resultText,
                    new PostLinkable.Value.StringValue(resultText),
                    link
            );

            post.addLinkable(pl);
            spansToApply.add(pl);
        }

        if (!spansToApply.isEmpty()) {
            resultText = applySpan(resultText, spansToApply);
        }

        // Apply break if not the last element.
        if (blockElement && element.nextSibling() != null) {
            resultText = TextUtils.concat(resultText, "\n");
        }

        if (linkify && post != null) {
            CommentParserHelper.detectLinks(
                    post,
                    resultText.toString(),
                    new SpannableString(resultText)
            );
        }

        return resultText;
    }

    private SpannableString applySpan(CharSequence text, List<Object> spans) {
        SpannableString result = new SpannableString(text);

        for (Object span : spans) {
            if (span != null) {
                // priority is 0 by default which is maximum above all else; higher priority is
                // like higher layers, i.e. 2 is above 1, 3 is above 2, etc.
                // we use 1000 here for to go above everything else
                result.setSpan(
                        span,
                        0,
                        result.length(),
                        (1000 << Spanned.SPAN_PRIORITY_SHIFT) & Spanned.SPAN_PRIORITY
                );
            }
        }
        return result;
    }

    public interface Action {
        CharSequence execute(
                PostParser.Callback callback,
                ChanPostBuilder post,
                CharSequence text,
                Element element
        );
    }
}
