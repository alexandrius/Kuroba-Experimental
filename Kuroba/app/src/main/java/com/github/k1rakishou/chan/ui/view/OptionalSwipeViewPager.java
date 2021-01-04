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
package com.github.k1rakishou.chan.ui.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.viewpager.widget.ViewPager;

import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils;
import com.github.k1rakishou.chan.utils.ViewUtils;
import com.github.k1rakishou.core_themes.ThemeEngine;

import javax.inject.Inject;

public class OptionalSwipeViewPager extends ViewPager {
    private boolean swipingEnabled;

    @Inject
    ThemeEngine themeEngine;

    public OptionalSwipeViewPager(Context context) {
        super(context);
        init();
    }

    public OptionalSwipeViewPager(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        AppModuleAndroidUtils.extractActivityComponent(getContext())
                .inject(this);

        ViewUtils.changeEdgeEffect(this, themeEngine.chanTheme);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return swipingEnabled && super.onTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        try {
            return swipingEnabled && super.onInterceptTouchEvent(ev);
        } catch (IllegalArgumentException ignored) {
            // Ignore pointer index out of range exceptions
            return false;
        }
    }

    public void setSwipingEnabled(boolean swipingEnabled) {
        this.swipingEnabled = swipingEnabled;
    }
}
