package com.github.k1rakishou.chan.features.drawer

import com.github.k1rakishou.ChanSettings
import com.github.k1rakishou.chan.core.base.BasePresenter
import com.github.k1rakishou.chan.core.base.DebouncingCoroutineExecutor
import com.github.k1rakishou.chan.core.manager.ArchivesManager
import com.github.k1rakishou.chan.core.manager.BookmarksManager
import com.github.k1rakishou.chan.core.manager.ChanThreadManager
import com.github.k1rakishou.chan.core.manager.HistoryNavigationManager
import com.github.k1rakishou.chan.core.manager.PageRequestManager
import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.chan.features.drawer.data.HistoryControllerState
import com.github.k1rakishou.chan.features.drawer.data.NavHistoryBookmarkAdditionalInfo
import com.github.k1rakishou.chan.features.drawer.data.NavigationHistoryEntry
import com.github.k1rakishou.chan.utils.BackgroundUtils
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.common.errorMessageOrClassName
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.navigation.NavHistoryElement
import com.github.k1rakishou.model.util.ChanPostUtils
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.processors.BehaviorProcessor
import io.reactivex.processors.PublishProcessor
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import okhttp3.HttpUrl
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

class DrawerPresenter(
  private val isDevFlavor: Boolean,
  private val historyNavigationManager: HistoryNavigationManager,
  private val siteManager: SiteManager,
  private val bookmarksManager: BookmarksManager,
  private val pageRequestManager: PageRequestManager,
  private val archivesManager: ArchivesManager,
  private val chanThreadManager: ChanThreadManager
) : BasePresenter<DrawerView>() {

  private val historyControllerStateSubject = PublishProcessor.create<HistoryControllerState>()
    .toSerialized()
  private val bookmarksBadgeStateSubject = BehaviorProcessor.createDefault(BookmarksBadgeState(0, false))

  private val reloadNavHistoryDebouncer = DebouncingCoroutineExecutor(scope)

  @OptIn(ExperimentalTime::class)
  override fun onCreate(view: DrawerView) {
    super.onCreate(view)

    scope.launch {
      setState(HistoryControllerState.Loading)

      historyNavigationManager.listenForNavigationStackChanges()
        .asFlow()
        .collect { reloadNavigationHistory() }
    }

    scope.launch {
      bookmarksManager.listenForBookmarksChanges()
        .onBackpressureLatest()
        .asFlow()
        .debounce(1.seconds)
        .collect { bookmarkChange ->
          bookmarksManager.awaitUntilInitialized()

          updateBadge()
          handleEvents(bookmarkChange)
          reloadNavigationHistory()
        }
    }
  }

  private fun handleEvents(bookmarkChange: BookmarksManager.BookmarkChange?) {
    if (bookmarkChange is BookmarksManager.BookmarkChange.BookmarksCreated) {
      val newNavigationElements = bookmarkChange.threadDescriptors
        .mapNotNull { threadDescriptor -> createNewNavigationElement(threadDescriptor) }

      historyNavigationManager.createNewNavElements(newNavigationElements)
      return
    }

    if (bookmarkChange is BookmarksManager.BookmarkChange.BookmarksDeleted) {
      historyNavigationManager.removeNavElements(bookmarkChange.threadDescriptors)
    }
  }

  fun mapBookmarksIntoNewNavigationElements(): List<HistoryNavigationManager.NewNavigationElement> {
    return bookmarksManager.mapNotNullAllBookmarks { threadBookmarkView ->
      createNewNavigationElement(threadBookmarkView.threadDescriptor)
    }
  }

  private fun createNewNavigationElement(
    threadDescriptor: ChanDescriptor.ThreadDescriptor
  ): HistoryNavigationManager.NewNavigationElement? {
    if (!historyNavigationManager.canCreateNavElement(bookmarksManager, threadDescriptor)) {
      return null
    }

    val chanOriginalPost = chanThreadManager.getChanThread(threadDescriptor)
      ?.getOriginalPost()

    var opThumbnailUrl: HttpUrl? = null
    var title: String? = null

    if (chanOriginalPost != null) {
      opThumbnailUrl = chanThreadManager.getChanThread(threadDescriptor)
        ?.getOriginalPost()
        ?.firstImage()
        ?.actualThumbnailUrl

      title = ChanPostUtils.getTitle(
        chanOriginalPost,
        threadDescriptor
      )
    } else {
      bookmarksManager.viewBookmark(threadDescriptor) { threadBookmarkView ->
        opThumbnailUrl = threadBookmarkView.thumbnailUrl
        title = threadBookmarkView.title
      }
    }

    if (opThumbnailUrl == null || title.isNullOrEmpty()) {
      return null
    }

    return HistoryNavigationManager.NewNavigationElement(
      threadDescriptor,
      opThumbnailUrl!!,
      title!!
    )
  }

  fun onThemeChanged() {
    updateBadge()
  }

  fun listenForStateChanges(): Flowable<HistoryControllerState> {
    return historyControllerStateSubject
      .onBackpressureLatest()
      .observeOn(AndroidSchedulers.mainThread())
      .distinctUntilChanged()
      .hide()
  }

  fun listenForBookmarksBadgeStateChanges(): Flowable<BookmarksBadgeState> {
    return bookmarksBadgeStateSubject
      .onBackpressureLatest()
      .observeOn(AndroidSchedulers.mainThread())
      .hide()
  }

  fun onNavElementSwipedAway(descriptor: ChanDescriptor) {
    if (descriptor is ChanDescriptor.ThreadDescriptor) {
      if (bookmarksManager.exists(descriptor)) {
        bookmarksManager.deleteBookmark(descriptor)
      }
    }

    historyNavigationManager.onNavElementSwipedAway(descriptor)
  }

  fun reloadNavigationHistory() {
    reloadNavHistoryDebouncer.post(HISTORY_NAV_ELEMENTS_DEBOUNCE_TIMEOUT_MS) {
      ModularResult.Try { showNavigationHistoryInternal() }.safeUnwrap { error ->
        Logger.e(TAG, "showNavigationHistoryInternal() error", error)
        setState(HistoryControllerState.Error(error.errorMessageOrClassName()))

        return@post
      }
    }
  }

  private suspend fun showNavigationHistoryInternal() {
    BackgroundUtils.ensureMainThread()
    historyNavigationManager.awaitUntilInitialized()

    val isWatcherEnabled = ChanSettings.watchEnabled.get()

    val navHistoryList = historyNavigationManager.getAll()
      .mapNotNull { navigationElement -> createNavHistoryElementOrNull(navigationElement, isWatcherEnabled) }

    if (navHistoryList.isEmpty()) {
      setState(HistoryControllerState.Empty)
      return
    }

    setState(HistoryControllerState.Data(navHistoryList))
  }

  private fun createNavHistoryElementOrNull(
    navigationElement: NavHistoryElement,
    isWatcherEnabled: Boolean
  ): NavigationHistoryEntry? {
    val siteDescriptor = when (navigationElement) {
      is NavHistoryElement.Catalog -> navigationElement.descriptor.siteDescriptor()
      is NavHistoryElement.Thread -> navigationElement.descriptor.siteDescriptor()
    }

    val siteEnabled = siteManager.bySiteDescriptor(siteDescriptor)?.enabled() ?: false
    if (!siteEnabled) {
      return null
    }

    val descriptor = when (navigationElement) {
      is NavHistoryElement.Catalog -> navigationElement.descriptor
      is NavHistoryElement.Thread -> navigationElement.descriptor
    }

    val canCreateNavElement = historyNavigationManager.canCreateNavElement(
      bookmarksManager,
      descriptor
    )

    if (!canCreateNavElement) {
      return null
    }

    val isSiteArchive = archivesManager.isSiteArchive(descriptor.siteDescriptor())

    val additionalInfo = if (canShowBookmarkInfo(isWatcherEnabled, descriptor, isSiteArchive)) {
      val threadDescriptor = descriptor as ChanDescriptor.ThreadDescriptor

      bookmarksManager.mapBookmark(threadDescriptor) { threadBookmarkView ->
        val boardPage = pageRequestManager.getPage(threadBookmarkView.threadDescriptor)

        return@mapBookmark NavHistoryBookmarkAdditionalInfo(
          watching = threadBookmarkView.isWatching(),
          newPosts = threadBookmarkView.newPostsCount(),
          newQuotes = threadBookmarkView.newQuotesCount(),
          isBumpLimit = threadBookmarkView.isBumpLimit(),
          isImageLimit = threadBookmarkView.isImageLimit(),
          isLastPage = boardPage?.isLastPage() ?: false,
        )
      }
    } else {
      null
    }

    val siteThumbnailUrl = if (descriptor is ChanDescriptor.ThreadDescriptor) {
      siteManager.bySiteDescriptor(siteDescriptor)?.icon()?.url
    } else {
      null
    }

    return NavigationHistoryEntry(
      descriptor,
      navigationElement.navHistoryElementInfo.thumbnailUrl,
      siteThumbnailUrl,
      navigationElement.navHistoryElementInfo.title,
      additionalInfo
    )
  }

  private fun canShowBookmarkInfo(
    isWatcherEnabled: Boolean,
    descriptor: ChanDescriptor,
    isSiteArchive: Boolean
  ) = isWatcherEnabled && descriptor is ChanDescriptor.ThreadDescriptor && !isSiteArchive

  fun updateBadge() {
    if (!bookmarksManager.isReady()) {
      return
    }

    val totalUnseenPostsCount = bookmarksManager.getTotalUnseenPostsCount()
    val hasUnreadReplies = bookmarksManager.hasUnreadReplies()

    if (isDevFlavor && totalUnseenPostsCount == 0) {
      check(!hasUnreadReplies) { "Bookmarks have no unread posts but have unseen replies!" }
    }

    bookmarksBadgeStateSubject.onNext(BookmarksBadgeState(totalUnseenPostsCount, hasUnreadReplies))
  }

  private fun setState(state: HistoryControllerState) {
    historyControllerStateSubject.onNext(state)
  }

  data class BookmarksBadgeState(
    val totalUnseenPostsCount: Int,
    val hasUnreadReplies: Boolean
  )

  companion object {
    private const val TAG = "DrawerPresenter"
    private const val HISTORY_NAV_ELEMENTS_DEBOUNCE_TIMEOUT_MS = 100L
  }

}