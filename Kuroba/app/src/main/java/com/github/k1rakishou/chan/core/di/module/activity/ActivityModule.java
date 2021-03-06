package com.github.k1rakishou.chan.core.di.module.activity;

import androidx.appcompat.app.AppCompatActivity;

import com.github.k1rakishou.chan.core.base.okhttp.ProxiedOkHttpClient;
import com.github.k1rakishou.chan.core.cache.FileCacheV2;
import com.github.k1rakishou.chan.core.di.scope.PerActivity;
import com.github.k1rakishou.chan.core.helper.DialogFactory;
import com.github.k1rakishou.chan.core.helper.StartActivityStartupHandlerHelper;
import com.github.k1rakishou.chan.core.manager.BoardManager;
import com.github.k1rakishou.chan.core.manager.BookmarksManager;
import com.github.k1rakishou.chan.core.manager.ChanFilterManager;
import com.github.k1rakishou.chan.core.manager.ChanThreadViewableInfoManager;
import com.github.k1rakishou.chan.core.manager.HistoryNavigationManager;
import com.github.k1rakishou.chan.core.manager.SettingsNotificationManager;
import com.github.k1rakishou.chan.core.manager.SiteManager;
import com.github.k1rakishou.chan.core.manager.UpdateManager;
import com.github.k1rakishou.chan.core.site.SiteResolver;
import com.github.k1rakishou.chan.ui.helper.RuntimePermissionsHelper;
import com.github.k1rakishou.fsaf.FileChooser;
import com.github.k1rakishou.fsaf.FileManager;

import dagger.Module;
import dagger.Provides;

@Module
public class ActivityModule {

    @Provides
    @PerActivity
    public UpdateManager provideUpdateManager(
            AppCompatActivity activity,
            FileCacheV2 fileCacheV2,
            FileManager fileManager,
            SettingsNotificationManager settingsNotificationManager,
            FileChooser fileChooser,
            ProxiedOkHttpClient proxiedOkHttpClient,
            DialogFactory dialogFactory,
            SiteManager siteManager,
            BoardManager boardManager
    ) {
        return new UpdateManager(
                activity,
                fileCacheV2,
                fileManager,
                settingsNotificationManager,
                fileChooser,
                proxiedOkHttpClient,
                dialogFactory,
                siteManager,
                boardManager
        );
    }

    @Provides
    @PerActivity
    public RuntimePermissionsHelper provideRuntimePermissionHelper(
            AppCompatActivity activity,
            DialogFactory dialogFactory
    ) {
        return new RuntimePermissionsHelper(
                activity,
                dialogFactory
        );
    }

    @Provides
    @PerActivity
    public StartActivityStartupHandlerHelper provideStartActivityStartupHandlerHelper(
            HistoryNavigationManager historyNavigationManager,
            SiteManager siteManager,
            BoardManager boardManager,
            BookmarksManager bookmarksManager,
            ChanFilterManager chanFilterManager,
            ChanThreadViewableInfoManager chanThreadViewableInfoManager,
            SiteResolver siteResolver
    ) {
        return new StartActivityStartupHandlerHelper(
                historyNavigationManager,
                siteManager,
                boardManager,
                bookmarksManager,
                chanFilterManager,
                chanThreadViewableInfoManager,
                siteResolver
        );
    }
}
