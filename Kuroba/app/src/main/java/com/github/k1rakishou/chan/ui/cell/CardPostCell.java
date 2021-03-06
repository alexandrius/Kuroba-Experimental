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
package com.github.k1rakishou.chan.ui.cell;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.github.k1rakishou.ChanSettings;
import com.github.k1rakishou.chan.R;
import com.github.k1rakishou.chan.core.manager.PostFilterManager;
import com.github.k1rakishou.chan.ui.layout.FixedRatioLinearLayout;
import com.github.k1rakishou.chan.ui.theme.widget.ColorizableCardView;
import com.github.k1rakishou.chan.ui.view.PostImageThumbnailView;
import com.github.k1rakishou.chan.ui.view.ThumbnailView;
import com.github.k1rakishou.chan.ui.view.floating_menu.FloatingListMenuItem;
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils;
import com.github.k1rakishou.common.AndroidUtils;
import com.github.k1rakishou.core_themes.ChanTheme;
import com.github.k1rakishou.core_themes.ThemeEngine;
import com.github.k1rakishou.model.data.board.pages.BoardPage;
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor;
import com.github.k1rakishou.model.data.post.ChanPost;
import com.github.k1rakishou.model.data.post.ChanPostImage;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import static com.github.k1rakishou.chan.ui.adapter.PostsFilter.Order.isNotBumpOrder;
import static com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.dp;
import static com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.getString;

public class CardPostCell extends ColorizableCardView implements PostCellInterface,
        View.OnClickListener, View.OnLongClickListener {

    private static final int COMMENT_MAX_LENGTH = 200;
    public static final int HI_RES_THUMBNAIL_SIZE = dp(160);

    @Inject
    ThemeEngine themeEngine;
    @Inject
    PostFilterManager postFilterManager;

    private ChanTheme theme;
    private ChanPost post;
    private PostCellInterface.PostCellCallback callback;
    private boolean compact = false;
    private boolean inPopup = false;

    private PostImageThumbnailView thumbView;
    private TextView title;
    private TextView comment;
    private TextView replies;
    private ImageView options;
    private View filterMatchColor;

    public CardPostCell(Context context) {
        super(context);
        init();
    }

    public CardPostCell(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CardPostCell(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        AppModuleAndroidUtils.extractActivityComponent(getContext())
                .inject(this);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        FixedRatioLinearLayout content = findViewById(R.id.card_content);
        content.setRatio(9f / 18f);
        thumbView = findViewById(R.id.thumbnail);
        thumbView.setRatio(16f / 13f);
        thumbView.setOnClickListener(this);
        thumbView.setOnLongClickListener(this);
        title = findViewById(R.id.title);
        comment = findViewById(R.id.comment);
        replies = findViewById(R.id.replies);
        options = findViewById(R.id.options);

        AndroidUtils.setBoundlessRoundRippleBackground(options);
        filterMatchColor = findViewById(R.id.filter_match_color);

        setOnClickListener(this);
        setCompact(compact);

        options.setOnClickListener(v -> {
            List<FloatingListMenuItem> items = new ArrayList<>();

            if (callback != null && post != null) {
                callback.onPopulatePostOptions(post, items);

                if (items.size() > 0) {
                    callback.showPostOptions(post, inPopup, items);
                }
            }
        });
    }

    @Override
    public void onClick(View v) {
        if (v == thumbView) {
            callback.onThumbnailClicked(post.firstImage(), thumbView);
        } else if (v == this) {
            callback.onPostClicked(post);
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (v == thumbView) {
            callback.onThumbnailLongClicked(post.firstImage(), thumbView);
            return true;
        }

        return false;
    }

    public void setPost(
            ChanDescriptor chanDescriptor,
            final ChanPost post,
            final int postIndex,
            PostCellInterface.PostCellCallback callback,
            boolean inPopup,
            boolean highlighted,
            boolean selected,
            long markedNo,
            boolean showDivider,
            ChanSettings.PostViewMode postViewMode,
            boolean compact,
            ChanTheme theme
    ) {
        if (post.equals(this.post) && theme.equals(this.theme) && inPopup == this.inPopup) {
            return;
        }

        this.inPopup = inPopup;
        this.post = post;
        this.theme = theme;
        this.callback = callback;

        bindPost(post);

        if (this.compact != compact) {
            this.compact = compact;
            setCompact(compact);
        }
    }

    public ChanPost getPost() {
        return post;
    }

    public ThumbnailView getThumbnailView(ChanPostImage postImage) {
        return thumbView;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void onPostRecycled(boolean isActuallyRecycling) {
        unbindPost(isActuallyRecycling);
    }

    private void unbindPost(boolean isActuallyRecycling) {
        if (post == null) {
            return;
        }

        thumbView.unbindPostImage();

        if (callback != null) {
            callback.onPostUnbind(post, isActuallyRecycling);
        }

        this.post = null;
        this.callback = null;
    }

    private void bindPost(ChanPost post) {
        if (callback == null) {
            throw new NullPointerException("Callback is null during bindPost()");
        }

        ChanPostImage firstPostImage = post.firstImage();
        if (firstPostImage != null && !ChanSettings.textOnly.get()) {
            thumbView.setVisibility(VISIBLE);

            int width = ChanSettings.highResCells.get()
                    ? Math.max(HI_RES_THUMBNAIL_SIZE, thumbView.getWidth())
                    : thumbView.getWidth();

            int height =  ChanSettings.highResCells.get()
                    ? Math.max(HI_RES_THUMBNAIL_SIZE, thumbView.getHeight())
                    : thumbView.getHeight();

            thumbView.bindPostImage(
                    firstPostImage,
                    width,
                    height
            );
        } else {
            thumbView.setVisibility(GONE);
            thumbView.unbindPostImage();
        }

        int filterHighlightedColor = postFilterManager.getFilterHighlightedColor(
                post.getPostDescriptor()
        );

        if (filterHighlightedColor != 0) {
            filterMatchColor.setVisibility(VISIBLE);
            filterMatchColor.setBackgroundColor(filterHighlightedColor);
        } else {
            filterMatchColor.setVisibility(GONE);
        }

        if (!TextUtils.isEmpty(post.getSubject())) {
            title.setVisibility(VISIBLE);
            title.setText(post.getSubject());
        } else {
            title.setVisibility(GONE);
            title.setText(null);
        }

        CharSequence commentText = post.getPostComment().comment();
        if (commentText.length() > COMMENT_MAX_LENGTH) {
            commentText = commentText.subSequence(0, COMMENT_MAX_LENGTH);
        }

        comment.setText(commentText);
        comment.setTextColor(themeEngine.getChanTheme().getTextColorPrimary());

        String status = getString(
                R.string.card_stats,
                post.getCatalogRepliesCount(),
                post.getCatalogImagesCount()
        );

        if (!ChanSettings.neverShowPages.get()) {
            BoardPage boardPage = callback.getPage(post.getPostDescriptor());
            if (boardPage != null && isNotBumpOrder(ChanSettings.boardOrder.get())) {
                status += " Pg " + boardPage.getCurrentPage();
            }
        }

        replies.setText(status);
        replies.setTextColor(themeEngine.getChanTheme().getTextColorSecondary());

        if (callback != null) {
            callback.onPostBind(post);
        }
    }

    private void setCompact(boolean compact) {
        int textReduction = compact ? -2 : 0;
        int textSizeSp = Integer.parseInt(ChanSettings.fontSize.get()) + textReduction;
        title.setTextSize(textSizeSp);
        comment.setTextSize(textSizeSp);
        replies.setTextSize(textSizeSp);

        int p = compact ? dp(3) : dp(8);

        // Same as the layout.
        title.setPadding(p, p, p, 0);
        comment.setPadding(p, p, p, 0);
        replies.setPadding(p, p / 2, p, p);

        int optionsPadding = compact ? 0 : dp(5);
        options.setPadding(0, optionsPadding, optionsPadding, 0);
        options.setImageTintList(ColorStateList.valueOf(themeEngine.getChanTheme().getTextColorHint()));
    }
}
