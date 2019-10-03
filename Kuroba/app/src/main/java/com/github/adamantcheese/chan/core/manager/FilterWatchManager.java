/*
 * Kuroba - *chan browser https://github.com/Adamantcheese/Kuroba/
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
package com.github.adamantcheese.chan.core.manager;

import com.github.adamantcheese.chan.core.database.DatabaseLoadableManager;
import com.github.adamantcheese.chan.core.database.DatabaseManager;
import com.github.adamantcheese.chan.core.model.ChanThread;
import com.github.adamantcheese.chan.core.model.Post;
import com.github.adamantcheese.chan.core.model.orm.Board;
import com.github.adamantcheese.chan.core.model.orm.Filter;
import com.github.adamantcheese.chan.core.model.orm.Loadable;
import com.github.adamantcheese.chan.core.model.orm.PinType;
import com.github.adamantcheese.chan.core.pool.ChanLoaderFactory;
import com.github.adamantcheese.chan.core.repository.BoardRepository;
import com.github.adamantcheese.chan.core.settings.ChanSettings;
import com.github.adamantcheese.chan.core.site.loader.ChanThreadLoader;
import com.github.adamantcheese.chan.ui.helper.PostHelper;
import com.github.adamantcheese.chan.utils.Logger;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.text.DateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

public class FilterWatchManager implements WakeManager.Wakeable {
    private static final String TAG = "FilterWatchManager";

    private final WakeManager wakeManager;
    private final FilterEngine filterEngine;
    private final WatchManager watchManager;
    private final ChanLoaderFactory chanLoaderFactory;
    private final BoardRepository boardRepository;
    private final DatabaseLoadableManager databaseLoadableManager;

    //filterLoaders keeps track of ChanThreadLoaders so they can be cleared correctly each alarm trigger
    //ignoredPosts keeps track of threads pinned by the filter manager and ignores them for future alarm triggers
    //this lets you unpin threads that are pinned by the filter pin manager and not have them come back
    //note that ignoredPosts is currently only saved while the application is running and not in the database
    private final Map<ChanThreadLoader, BackgroundLoader> filterLoaders = new HashMap<>();
    private final Set<Integer> ignoredPosts = Collections.synchronizedSet(new HashSet<>());
    //keep track of how many boards we've checked and their posts so we can cut out things from the ignored posts
    private int numBoardsChecked = 0;
    private Set<Post> lastCheckedPosts = Collections.synchronizedSet(new HashSet<>());
    private List<Filter> filters;
    private boolean processing = false;

    private final Gson serializer = new Gson();

    @Inject
    public FilterWatchManager(WakeManager wakeManager,
                              FilterEngine filterEngine,
                              WatchManager watchManager,
                              ChanLoaderFactory chanLoaderFactory,
                              BoardRepository boardRepository,
                              DatabaseManager databaseManager) {
        this.wakeManager = wakeManager;
        this.filterEngine = filterEngine;
        this.watchManager = watchManager;
        this.chanLoaderFactory = chanLoaderFactory;
        this.boardRepository = boardRepository;
        this.databaseLoadableManager = databaseManager.getDatabaseLoadableManager();

        if (ChanSettings.watchFilterWatch.get()) {
            wakeManager.registerWakeable(this);
        }

        Set<Integer> previousIgnore = serializer.fromJson(ChanSettings.filterWatchIgnored.get(), new TypeToken<Set<Integer>>() {
        }.getType());
        if (previousIgnore != null) ignoredPosts.addAll(previousIgnore);

        EventBus.getDefault().register(this);
    }

    @Subscribe
    public void onEvent(ChanSettings.SettingChanged<?> settingChanged) {
        if (settingChanged.setting == ChanSettings.watchFilterWatch) {
            if (ChanSettings.watchFilterWatch.get()) {
                wakeManager.registerWakeable(this);
            } else {
                wakeManager.unregisterWakeable(this);
            }
        }
    }

    @Override
    public void onWake() {
        if (!processing) {
            wakeManager.manageLock(true, FilterWatchManager.this);
            Logger.i(TAG, "Processing filter loaders, started at " + DateFormat.getTimeInstance().format(new Date()));
            processing = true;
            populateFilterLoaders();
            for (ChanThreadLoader loader : filterLoaders.keySet()) {
                loader.requestData();
            }
        }
    }

    private void populateFilterLoaders() {
        for (ChanThreadLoader loader : filterLoaders.keySet()) {
            chanLoaderFactory.release(loader, filterLoaders.get(loader));
        }
        filterLoaders.clear();
        //get our filters that are tagged as "pin"
        filters = filterEngine.getEnabledWatchFilters();
        //get a set of boards to background load
        Set<String> boardCodes = new HashSet<>();
        for (Filter f : filters) {
            //if the allBoards flag is set for any one filter, add all saved boards to the set
            if (f.allBoards) {
                for (BoardRepository.SiteBoards s : boardRepository.getSaved().get()) {
                    for (Board b : s.boards) {
                        boardCodes.add(b.code);
                    }
                }
                //shortcut out if any filter has the allBoards flag
                break;
            }
            boardCodes.addAll(Arrays.asList(f.boardCodesNoId()));
        }
        numBoardsChecked = boardCodes.size();
        //create background loaders for each thing in the board set
        for (BoardRepository.SiteBoards siteBoard : boardRepository.getSaved().get()) {
            for (Board b : siteBoard.boards) {
                for (String code : boardCodes)
                    if (b.code.equals(code)) {
                        BackgroundLoader backgroundLoader = new BackgroundLoader();
                        Loadable boardLoadable = Loadable.forCatalog(b);
                        boardLoadable = databaseLoadableManager.get(boardLoadable);
                        ChanThreadLoader catalogLoader = chanLoaderFactory.obtain(
                                boardLoadable,
                                watchManager,
                                backgroundLoader);
                        filterLoaders.put(catalogLoader, backgroundLoader);
                    }
            }
        }
    }

    public void onCatalogLoad(ChanThread catalog) {
        if (catalog.getLoadable().isThreadMode()) return; //not a catalog
        if (processing) return; //filter watch manager is currently processing, ignore

        Set<Integer> toAdd = new HashSet<>();
        //Match filters and ignores
        List<Filter> filters = filterEngine.getEnabledWatchFilters();
        for (Filter f : filters) {
            for (Post p : catalog.getPostsUnsafe()) {
                if (filterEngine.matches(f, p) && p.filterWatch && !ignoredPosts.contains(p.no)) {
                    Loadable pinLoadable = Loadable.forThread(catalog.getLoadable().site, p.board, p.no, PostHelper.getTitle(p, catalog.getLoadable()));
                    pinLoadable = databaseLoadableManager.get(pinLoadable);
                    watchManager.createPin(pinLoadable, p, PinType.WATCH_NEW_POSTS);
                    toAdd.add(p.no);
                }
            }
        }
        //add all posts to ignore
        ignoredPosts.addAll(toAdd);
        lastCheckedPosts.addAll(catalog.getPostsUnsafe());
        synchronized (this) {
            Set<Integer> lastCheckedPostNumbers = new HashSet<>();
            for (Post post : lastCheckedPosts) {
                lastCheckedPostNumbers.add(post.no);
            }
            ignoredPosts.retainAll(lastCheckedPostNumbers);
            ChanSettings.filterWatchIgnored.set(serializer.toJson(ignoredPosts));
            lastCheckedPosts.clear();
        }
    }

    private class BackgroundLoader implements ChanThreadLoader.ChanLoaderCallback {
        @Override
        public void onChanLoaderData(ChanThread result) {
            Set<Integer> toAdd = new HashSet<>();
            //Match filters and ignores
            for (Filter f : filters) {
                for (Post p : result.getPostsUnsafe()) {
                    if (filterEngine.matches(f, p) && p.filterWatch && !ignoredPosts.contains(p.no)) {
                        Loadable pinLoadable = Loadable.forThread(result.getLoadable().site, p.board, p.no, PostHelper.getTitle(p, result.getLoadable()));
                        pinLoadable = databaseLoadableManager.get(pinLoadable);
                        watchManager.createPin(pinLoadable, p, PinType.WATCH_NEW_POSTS);
                        toAdd.add(p.no);
                    }
                }
            }
            //add all posts to ignore
            ignoredPosts.addAll(toAdd);
            lastCheckedPosts.addAll(result.getPostsUnsafe());
            synchronized (this) {
                numBoardsChecked--;
                if (numBoardsChecked <= 0) {
                    numBoardsChecked = 0;
                    Set<Integer> lastCheckedPostNumbers = new HashSet<>();
                    for (Post post : lastCheckedPosts) {
                        lastCheckedPostNumbers.add(post.no);
                    }
                    ignoredPosts.retainAll(lastCheckedPostNumbers);
                    ChanSettings.filterWatchIgnored.set(serializer.toJson(ignoredPosts));
                    lastCheckedPosts.clear();
                    processing = false;
                    Logger.i(TAG, "Finished processing filter loaders, ended at " + DateFormat.getTimeInstance().format(new Date()));
                    wakeManager.manageLock(false, FilterWatchManager.this);
                }
            }
        }

        @Override
        public void onChanLoaderError(ChanThreadLoader.ChanLoaderException error) {
            //ignore all errors
        }
    }
}
