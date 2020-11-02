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
package com.github.k1rakishou.chan.core.di.module.application;

import com.github.k1rakishou.chan.core.manager.ArchivesManager;
import com.github.k1rakishou.chan.core.manager.BoardManager;
import com.github.k1rakishou.chan.core.manager.SiteManager;
import com.github.k1rakishou.chan.core.repository.ImportExportRepository;
import com.github.k1rakishou.chan.core.repository.LastReplyRepository;
import com.github.k1rakishou.chan.core.site.ParserRepository;
import com.github.k1rakishou.chan.core.site.parser.MockReplyManager;
import com.github.k1rakishou.chan.core.usecase.KurobaSettingsImportUseCase;
import com.github.k1rakishou.chan.ui.theme.ThemeEngine;
import com.github.k1rakishou.chan.utils.Logger;
import com.github.k1rakishou.fsaf.FileManager;
import com.google.gson.Gson;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class RepositoryModule {

    @Provides
    @Singleton
    public ImportExportRepository provideImportExportRepository(
            Gson gson,
            FileManager fileManager,
            KurobaSettingsImportUseCase kurobaSettingsImportUseCase
    ) {
        Logger.d(AppModule.DI_TAG, "Import export repository");
        return new ImportExportRepository(
                gson,
                fileManager,
                kurobaSettingsImportUseCase
        );
    }

    @Provides
    @Singleton
    public ParserRepository provideParserRepository(
            ThemeEngine themeEngine,
            MockReplyManager mockReplyManager,
            ArchivesManager archivesManager
    ) {
        Logger.d(AppModule.DI_TAG, "ParserRepository");
        return new ParserRepository(themeEngine, mockReplyManager, archivesManager);
    }

    @Provides
    @Singleton
    public LastReplyRepository provideLastReplyRepository(
            SiteManager siteManager,
            BoardManager boardManager
    ) {
        Logger.d(AppModule.DI_TAG, "Last reply repository");
        return new LastReplyRepository(siteManager, boardManager);
    }

}