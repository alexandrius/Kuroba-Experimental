package com.github.adamantcheese.chan.features.drawer

import com.github.adamantcheese.chan.Chan
import com.github.adamantcheese.chan.core.base.BasePresenter
import com.github.adamantcheese.chan.core.manager.BookmarksManager
import com.github.adamantcheese.chan.core.manager.HistoryNavigationManager
import com.github.adamantcheese.chan.core.repository.BoardRepository
import com.github.adamantcheese.chan.core.repository.SiteRepository
import com.github.adamantcheese.chan.features.drawer.data.HistoryControllerState
import com.github.adamantcheese.chan.features.drawer.data.NavigationHistoryEntry
import com.github.adamantcheese.chan.utils.BackgroundUtils
import com.github.adamantcheese.chan.utils.Logger
import com.github.adamantcheese.chan.utils.errorMessageOrClassName
import com.github.adamantcheese.common.ModularResult
import com.github.adamantcheese.model.data.descriptor.ChanDescriptor
import com.github.adamantcheese.model.data.navigation.NavHistoryElement
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.processors.BehaviorProcessor
import io.reactivex.processors.PublishProcessor
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class DrawerPresenter(
  private val isDevFlavor: Boolean
) : BasePresenter<DrawerView>() {

  @Inject
  lateinit var historyNavigationManager: HistoryNavigationManager
  @Inject
  lateinit var siteRepository: SiteRepository
  @Inject
  lateinit var boardRepository: BoardRepository
  @Inject
  lateinit var bookmarksManager: BookmarksManager

  private val historyControllerStateSubject = PublishProcessor.create<HistoryControllerState>()
    .toSerialized()
  private val bookmarksBadgeStateSubject = BehaviorProcessor.createDefault(BookmarksBadgeState(0, false))

  override fun onCreate(view: DrawerView) {
    super.onCreate(view)
    Chan.inject(this)

    scope.launch {
      setState(HistoryControllerState.Loading)

      historyNavigationManager.listenForNavigationStackChanges().asFlow()
        .collect {
          BackgroundUtils.ensureMainThread()

          ModularResult.Try { showNavigationHistory() }.safeUnwrap { error ->
            Logger.e(TAG, "showNavigationHistory() error", error)
            setState(HistoryControllerState.Error(error.errorMessageOrClassName()))

            return@collect
          }
        }
    }

    scope.launch {
      bookmarksManager.listenForBookmarksChanges()
        .debounce(1, TimeUnit.SECONDS)
        .asFlow()
        .collect { onBookmarksChanged() }
    }
  }

  fun listenForStateChanges(): Flowable<HistoryControllerState> {
    return historyControllerStateSubject
      .observeOn(AndroidSchedulers.mainThread())
      .distinctUntilChanged()
      .onBackpressureLatest()
      .hide()
  }

  fun listenForBookmarksBadgeStateChanges(): Flowable<BookmarksBadgeState> {
    return bookmarksBadgeStateSubject
      .observeOn(AndroidSchedulers.mainThread())
      .distinctUntilChanged()
      .onBackpressureLatest()
      .hide()
  }

  fun isCurrentlyVisible(descriptor: ChanDescriptor): Boolean {
    return historyNavigationManager.isAtTop(descriptor)
  }

  fun onNavElementSwipedAway(descriptor: ChanDescriptor) {
    historyNavigationManager.onNavElementRemoved(descriptor)
  }

  private suspend fun showNavigationHistory() {
    BackgroundUtils.ensureMainThread()
    historyNavigationManager.awaitUntilInitialized()

    val navHistoryList = historyNavigationManager.getAll().mapNotNull { navigationElement ->
      val siteDescriptor = when (navigationElement) {
        is NavHistoryElement.Catalog -> navigationElement.descriptor.siteDescriptor()
        is NavHistoryElement.Thread -> navigationElement.descriptor.siteDescriptor()
      }

      val boardDescriptor = when (navigationElement) {
        is NavHistoryElement.Catalog -> navigationElement.descriptor.boardDescriptor
        is NavHistoryElement.Thread -> navigationElement.descriptor.boardDescriptor
      }

      if (!siteRepository.containsSite(siteDescriptor)) {
        return@mapNotNull null
      }

      if (!boardRepository.containsBoard(boardDescriptor)) {
        return@mapNotNull null
      }

      val descriptor = when (navigationElement) {
        is NavHistoryElement.Catalog -> navigationElement.descriptor
        is NavHistoryElement.Thread -> navigationElement.descriptor
      }

      return@mapNotNull NavigationHistoryEntry(
        descriptor,
        navigationElement.navHistoryElementInfo.thumbnailUrl,
        navigationElement.navHistoryElementInfo.title
      )
    }

    if (navHistoryList.isEmpty()) {
      setState(HistoryControllerState.Empty)
      return
    }

    setState(HistoryControllerState.Data(navHistoryList))
  }

  private fun onBookmarksChanged() {
    val totalUnseenPostsCount = bookmarksManager.getTotalUnseenPostsCount()
    val hasUnseenReplies = bookmarksManager.hasUnseenReplies()

    if (isDevFlavor && totalUnseenPostsCount == 0) {
      // TODO(KurobaEx): enable this check after reply persistence is implemented
//      check(!hasUnseenReplies) { "Bookmarks have no unseen posts but have unseen replies!" }
    }

    bookmarksBadgeStateSubject.onNext(BookmarksBadgeState(totalUnseenPostsCount, hasUnseenReplies))
  }

  private fun setState(state: HistoryControllerState) {
    historyControllerStateSubject.onNext(state)
  }

  data class BookmarksBadgeState(
    val totalUnseenPostsCount: Int,
    val hasUnseenReplies: Boolean
  )

  companion object {
    private const val TAG = "DrawerPresenter"
  }

}