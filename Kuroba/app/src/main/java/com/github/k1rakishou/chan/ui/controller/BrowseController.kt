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
package com.github.k1rakishou.chan.ui.controller

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import com.github.k1rakishou.ChanSettings
import com.github.k1rakishou.ChanSettings.PostViewMode
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.controller.ui.NavigationControllerContainerLayout
import com.github.k1rakishou.chan.core.base.SerializedCoroutineExecutor
import com.github.k1rakishou.chan.core.di.component.activity.ActivityComponent
import com.github.k1rakishou.chan.core.helper.DialogFactory
import com.github.k1rakishou.chan.core.manager.BoardManager
import com.github.k1rakishou.chan.core.manager.HistoryNavigationManager
import com.github.k1rakishou.chan.core.presenter.BrowsePresenter
import com.github.k1rakishou.chan.features.drawer.DrawerCallbacks
import com.github.k1rakishou.chan.features.setup.BoardSelectionController
import com.github.k1rakishou.chan.features.setup.SiteSettingsController
import com.github.k1rakishou.chan.features.setup.SitesSetupController
import com.github.k1rakishou.chan.ui.adapter.PostsFilter
import com.github.k1rakishou.chan.ui.controller.ThreadSlideController.ReplyAutoCloseListener
import com.github.k1rakishou.chan.ui.controller.ThreadSlideController.SlideChangeListener
import com.github.k1rakishou.chan.ui.controller.navigation.SplitNavigationController
import com.github.k1rakishou.chan.ui.controller.navigation.StyledToolbarNavigationController
import com.github.k1rakishou.chan.ui.controller.navigation.ToolbarNavigationController
import com.github.k1rakishou.chan.ui.layout.ThreadLayout.ThreadLayoutCallback
import com.github.k1rakishou.chan.ui.misc.ConstraintLayoutBiasPair
import com.github.k1rakishou.chan.ui.toolbar.CheckableToolbarMenuSubItem
import com.github.k1rakishou.chan.ui.toolbar.NavigationItem
import com.github.k1rakishou.chan.ui.toolbar.ToolbarMenuItem
import com.github.k1rakishou.chan.ui.toolbar.ToolbarMenuSubItem
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.getString
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.inflate
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.isDevBuild
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.descriptor.BoardDescriptor
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor.CatalogDescriptor
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor.ThreadDescriptor
import com.github.k1rakishou.model.data.descriptor.SiteDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

class BrowseController(
  context: Context,
  drawerCallbacks: DrawerCallbacks?
) : ThreadController(context, drawerCallbacks),
  ThreadLayoutCallback,
  BrowsePresenter.Callback,
  SlideChangeListener,
  ReplyAutoCloseListener {

  @Inject
  lateinit var presenter: BrowsePresenter
  @Inject
  lateinit var boardManager: BoardManager
  @Inject
  lateinit var historyNavigationManager: HistoryNavigationManager
  @Inject
  lateinit var dialogFactory: DialogFactory

  private lateinit var serializedCoroutineExecutor: SerializedCoroutineExecutor

  private var order: PostsFilter.Order = PostsFilter.Order.BUMP
  private var initialized = false
  private var menuBuiltOnce = false

  override val threadControllerType: ThreadSlideController.ThreadControllerType
    get() = ThreadSlideController.ThreadControllerType.Catalog

  override fun injectDependencies(component: ActivityComponent) {
    component.inject(this)
  }

  override fun onCreate() {
    super.onCreate()

    val navControllerContainerLayout = inflate(context, R.layout.controller_browse)
    val container = navControllerContainerLayout.findViewById<View>(R.id.container) as NavigationControllerContainerLayout
    container.initBrowseControllerTracker(this, navigationController!!)
    container.addView(view)
    view = container

    // Navigation
    initNavigation()

    // Initialization
    serializedCoroutineExecutor = SerializedCoroutineExecutor(mainScope)

    serializedCoroutineExecutor.post {
      val boardOrder = ChanSettings.boardOrder.get()
      order = PostsFilter.Order.find(boardOrder) ?: PostsFilter.Order.BUMP

      threadLayout.setPostViewMode(ChanSettings.boardViewMode.get())
      threadLayout.presenter.setOrder(order)
    }
  }

  override fun onShow() {
    super.onShow()

    if (drawerCallbacks != null) {
      drawerCallbacks!!.resetBottomNavViewCheckState()

      if (ChanSettings.getCurrentLayoutMode() != ChanSettings.LayoutMode.SPLIT) {
        drawerCallbacks!!.showBottomNavBar(unlockTranslation = false, unlockCollapse = false)
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()

    drawerCallbacks = null
    presenter.destroy()
  }

  override suspend fun showSitesNotSetup() {
    super.showSitesNotSetup()

    // this controller is used for catalog views; displaying things on two rows for them middle
    // menu is how we want it done these need to be setup before the view is rendered,
    // otherwise the subtitle view is removed
    navigation.title = getString(R.string.browse_controller_title_app_setup)
    navigation.subtitle = getString(R.string.browse_controller_subtitle)
    buildMenu()

    initialized = true
  }

  suspend fun setBoard(descriptor: BoardDescriptor) {
    Logger.d(TAG, "setBoard($descriptor)")

    presenter.loadBoard(descriptor)
    initialized = true
  }

  suspend fun loadWithDefaultBoard() {
    Logger.d(TAG, "loadWithDefaultBoard()")

    presenter.loadWithDefaultBoard()
    initialized = true
  }

  private fun initNavigation() {
    // Navigation item
    navigation.hasDrawer = true
    navigation.setMiddleMenu {
      if (!initialized) {
        return@setMiddleMenu
      }

      if (!siteManager.areSitesSetup()) {
        openSitesSetupController()
      } else {
        openBoardSelectionController()
      }
    }

    // Toolbar menu
    navigation.hasBack = false

    // this controller is used for catalog views; displaying things on two rows for them middle
    // menu is how we want it done these need to be setup before the view is rendered,
    // otherwise the subtitle view is removed
    navigation.title = getString(R.string.loading)
    requireNavController().requireToolbar().updateTitle(navigation)

    // Presenter
    presenter.create(mainScope, this)
  }

  private fun openBoardSelectionController() {
    val boardSelectionController = BoardSelectionController(
      context,
      object : BoardSelectionController.UserSelectionListener {
        override fun onOpenSitesSettingsClicked() {
          openSitesSetupController()
        }

        override fun onSiteSelected(siteDescriptor: SiteDescriptor) {
          openSiteSettingsController(siteDescriptor)
        }

        override fun onBoardSelected(boardDescriptor: BoardDescriptor) {
          if (boardManager.currentBoardDescriptor() == boardDescriptor) {
            return
          }

          mainScope.launch(Dispatchers.Main.immediate) { loadBoard(boardDescriptor) }
        }
      })

    navigationController!!.presentController(boardSelectionController)
  }

  private fun openSitesSetupController() {
    val sitesSetupController = SitesSetupController(context)
    if (doubleNavigationController != null) {
      doubleNavigationController!!.openControllerWrappedIntoBottomNavAwareController(sitesSetupController)
    } else {
      requireStartActivity().openControllerWrappedIntoBottomNavAwareController(sitesSetupController)
    }

    requireStartActivity().setSettingsMenuItemSelected()
  }

  private fun openSiteSettingsController(siteDescriptor: SiteDescriptor) {
    val siteSettingsController = SiteSettingsController(context, siteDescriptor)
    if (doubleNavigationController != null) {
      doubleNavigationController!!.openControllerWrappedIntoBottomNavAwareController(siteSettingsController)
    } else {
      requireStartActivity().openControllerWrappedIntoBottomNavAwareController(siteSettingsController)
    }

    requireStartActivity().setSettingsMenuItemSelected()
  }

  @Suppress("MoveLambdaOutsideParentheses")
  private fun buildMenu() {
    val gravity = if (ChanSettings.getCurrentLayoutMode() == ChanSettings.LayoutMode.SPLIT) {
      ConstraintLayoutBiasPair.TopLeft
    } else {
      ConstraintLayoutBiasPair.Top
    }

    val menuBuilder = navigation.buildMenu(gravity)
      .withItem(R.drawable.ic_search_white_24dp) { item -> searchClicked(item) }
      .withItem(R.drawable.ic_refresh_white_24dp) { item -> reloadClicked(item) }

    val overflowBuilder = menuBuilder.withOverflow(requireNavController())
    if (!ChanSettings.enableReplyFab.get()) {
      overflowBuilder.withSubItem(ACTION_REPLY, R.string.action_reply) { item -> replyClicked(item) }
    }

    val modeStringId = if (ChanSettings.boardViewMode.get() == PostViewMode.LIST) {
      R.string.action_switch_catalog
    } else {
      R.string.action_switch_board
    }

    overflowBuilder
      .withSubItem(ACTION_CHANGE_VIEW_MODE, modeStringId) { item -> viewModeClicked(item) }
      .addSortMenu()
      .addDevMenu()
      .withSubItem(ACTION_OPEN_BROWSER, R.string.action_open_browser, { item -> openBrowserClicked(item) })
      .withSubItem(ACTION_OPEN_THREAD_BY_ID, R.string.action_open_thread_by_id, { item -> openThreadById(item) })
      .withSubItem(ACTION_SHARE, R.string.action_share, { item -> shareClicked(item) })
      .withSubItem(ACTION_SCROLL_TO_TOP, R.string.action_scroll_to_top, { item -> upClicked(item) })
      .withSubItem(ACTION_SCROLL_TO_BOTTOM, R.string.action_scroll_to_bottom, { item -> downClicked(item) })
      .build()
      .build()

    requireNavController().requireToolbar().setNavigationItem(
      false,
      true,
      navigation,
      themeEngine.chanTheme
    )
  }

  @Suppress("MoveLambdaOutsideParentheses")
  private fun NavigationItem.MenuOverflowBuilder.addDevMenu(): NavigationItem.MenuOverflowBuilder {
    withNestedOverflow(
      ACTION_DEV_MENU,
      R.string.action_browse_dev_menu,
      isDevBuild()
    )
      .addNestedItem(
        DEV_BOOKMARK_EVERY_THREAD,
        R.string.dev_bookmark_every_thread,
        true,
        DEV_BOOKMARK_EVERY_THREAD,
        { subItem -> onBookmarkEveryThreadClicked(subItem) }
      )
      .build()

    return this
  }

  @Suppress("MoveLambdaOutsideParentheses")
  private fun NavigationItem.MenuOverflowBuilder.addSortMenu(): NavigationItem.MenuOverflowBuilder {
    var currentOrder = PostsFilter.Order.find(ChanSettings.boardOrder.get())
    if (currentOrder == null) {
      currentOrder = PostsFilter.Order.BUMP
    }

    withNestedOverflow(ACTION_SORT, R.string.action_sort, true)
      .addNestedCheckableItem(
        SORT_MODE_BUMP,
        R.string.order_bump,
        true,
        currentOrder == PostsFilter.Order.BUMP,
        PostsFilter.Order.BUMP,
        { subItem -> onSortItemClicked(subItem) }
      )
      .addNestedCheckableItem(
        SORT_MODE_REPLY,
        R.string.order_reply,
        true,
        currentOrder == PostsFilter.Order.REPLY,
        PostsFilter.Order.REPLY,
        { subItem -> onSortItemClicked(subItem) }
      )
      .addNestedCheckableItem(
        SORT_MODE_IMAGE,
        R.string.order_image,
        true,
        currentOrder == PostsFilter.Order.IMAGE,
        PostsFilter.Order.IMAGE,
        { subItem -> onSortItemClicked(subItem) }
      )
      .addNestedCheckableItem(
        SORT_MODE_NEWEST,
        R.string.order_newest,
        true,
        currentOrder == PostsFilter.Order.IMAGE,
        PostsFilter.Order.NEWEST,
        { subItem -> onSortItemClicked(subItem) }
      )
      .addNestedCheckableItem(
        SORT_MODE_OLDEST,
        R.string.order_oldest,
        true,
        currentOrder == PostsFilter.Order.OLDEST,
        PostsFilter.Order.OLDEST,
        { subItem -> onSortItemClicked(subItem) }
      )
      .addNestedCheckableItem(
        SORT_MODE_MODIFIED,
        R.string.order_modified,
        true,
        currentOrder == PostsFilter.Order.MODIFIED,
        PostsFilter.Order.MODIFIED,
        { subItem -> onSortItemClicked(subItem) }
      )
      .addNestedCheckableItem(
        SORT_MODE_ACTIVITY,
        R.string.order_activity,
        true,
        currentOrder == PostsFilter.Order.ACTIVITY,
        PostsFilter.Order.ACTIVITY,
        { subItem -> onSortItemClicked(subItem) }
      )
      .build()

    return this
  }

  private fun onBookmarkEveryThreadClicked(subItem: ToolbarMenuSubItem) {
    val id = subItem.value as? Int
      ?: return

    when (id) {
      DEV_BOOKMARK_EVERY_THREAD -> {
        presenter.bookmarkEveryThread(threadLayout.presenter.currentChanDescriptor)
      }
    }
  }

  private fun onSortItemClicked(subItem: ToolbarMenuSubItem) {
    serializedCoroutineExecutor.post {
      val order = subItem.value as? PostsFilter.Order
        ?: return@post

      ChanSettings.boardOrder.set(order.orderName)
      this@BrowseController.order = order

      navigation.findSubItem(ACTION_SORT)?.let { sortSubItem ->
        resetSelectedSortOrderItem(sortSubItem)
      }

      subItem as CheckableToolbarMenuSubItem
      subItem.isChecked = true

      val presenter = threadLayout.presenter
      presenter.setOrder(order)
    }
  }

  private fun resetSelectedSortOrderItem(item: ToolbarMenuSubItem) {
    if (item is CheckableToolbarMenuSubItem) {
      item.isChecked = false
    }

    for (nestedItem in item.moreItems) {
      resetSelectedSortOrderItem(nestedItem as CheckableToolbarMenuSubItem)
    }
  }

  private fun searchClicked(item: ToolbarMenuItem) {
    val presenter = threadLayout.presenter
    if (!presenter.isBound) {
      return
    }

    val refreshView: View = item.view
    refreshView.scaleX = 1f
    refreshView.scaleY = 1f
    refreshView.animate()
      .scaleX(10f)
      .scaleY(10f)
      .setDuration(500)
      .setInterpolator(AccelerateInterpolator(2f))
      .setListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
          refreshView.scaleX = 1f
          refreshView.scaleY = 1f
        }
      })

    (navigationController as ToolbarNavigationController).showSearch()
  }

  private fun reloadClicked(item: ToolbarMenuItem) {
    val presenter = threadLayout.presenter
    if (!presenter.isBound) {
      return
    }

    presenter.normalLoad(showLoading = true, requestNewPostsFromServer = true)

    // Give the rotation menu item view a spin.
    val refreshView: View = item.view
    // Disable the ripple effect until the animation ends, but turn it back on so tap/hold ripple works
    refreshView.setBackgroundResource(0)

    val animation: Animation = RotateAnimation(
      0f,
      360f,
      RotateAnimation.RELATIVE_TO_SELF,
      0.5f,
      RotateAnimation.RELATIVE_TO_SELF,
      0.5f
    )

    animation.duration = 500L
    animation.setAnimationListener(object : Animation.AnimationListener {
      override fun onAnimationStart(animation: Animation) {}
      override fun onAnimationEnd(animation: Animation) {
        refreshView.setBackgroundResource(R.drawable.item_background)
      }

      override fun onAnimationRepeat(animation: Animation) {}
    })

    refreshView.startAnimation(animation)
  }

  private fun replyClicked(item: ToolbarMenuSubItem) {
    threadLayout.openReply(true)
  }

  private fun viewModeClicked(item: ToolbarMenuSubItem) {
    var postViewMode = ChanSettings.boardViewMode.get()

    postViewMode = if (postViewMode == PostViewMode.LIST) {
      PostViewMode.CARD
    } else {
      PostViewMode.LIST
    }

    ChanSettings.boardViewMode.set(postViewMode)

    val viewModeText = if (postViewMode == PostViewMode.LIST) {
      R.string.action_switch_catalog
    } else {
      R.string.action_switch_board
    }

    item.text = getString(viewModeText)
    threadLayout.setPostViewMode(postViewMode)
  }

  private fun openBrowserClicked(item: ToolbarMenuSubItem) {
    handleShareOrOpenInBrowser(false)
  }

  private fun openThreadById(item: ToolbarMenuSubItem) {
    if (chanDescriptor == null) {
      return
    }

    dialogFactory.createSimpleDialogWithInput(
      context = context,
      titleTextId = R.string.browse_controller_enter_thread_id,
      descriptionTextId = R.string.browse_controller_enter_thread_id_msg,
      onValueEntered = { input: String -> openThreadByIdInternal(input) },
      inputType = DialogFactory.DialogInputType.Integer
    )
  }

  private fun openThreadByIdInternal(input: String) {
    mainScope.launch(Dispatchers.Main.immediate) {
      try {
        val threadDescriptor = ThreadDescriptor.create(
          chanDescriptor!!.siteName(),
          chanDescriptor!!.boardCode(),
          input.toLong()
        )

        showThread(threadDescriptor, true)
      } catch (e: NumberFormatException) {
        showToast(context.getString(R.string.browse_controller_error_parsing_thread_id))
      }
    }
  }

  private fun shareClicked(item: ToolbarMenuSubItem) {
    handleShareOrOpenInBrowser(true)
  }

  private fun upClicked(item: ToolbarMenuSubItem) {
    threadLayout.presenter.scrollTo(0, false)
  }

  private fun downClicked(item: ToolbarMenuSubItem) {
    threadLayout.presenter.scrollTo(-1, false)
  }

  override fun onReplyViewShouldClose() {
    threadLayout.openReply(false)
  }

  private fun handleShareOrOpenInBrowser(share: Boolean) {
    val presenter = threadLayout.presenter
    if (!presenter.isBound) {
      return
    }

    if (presenter.currentChanDescriptor == null) {
      Logger.e(TAG, "handleShareOrOpenInBrowser() chanThread == null")
      showToast(R.string.cannot_open_in_browser_already_deleted)
      return
    }

    val chanDescriptor = presenter.currentChanDescriptor
    if (chanDescriptor == null) {
      Logger.e(TAG, "handleShareOrOpenInBrowser() chanDescriptor == null")
      showToast(R.string.cannot_open_in_browser_already_deleted)
      return
    }

    val site = siteManager.bySiteDescriptor(chanDescriptor.siteDescriptor())
    if (site == null) {
      Logger.e(TAG, "handleShareOrOpenInBrowser() site == null " +
        "(siteDescriptor = ${chanDescriptor.siteDescriptor()})")
      showToast(R.string.cannot_open_in_browser_already_deleted)
      return
    }

    val link = site.resolvable().desktopUrl(chanDescriptor, null)
    if (share) {
      AppModuleAndroidUtils.shareLink(link)
    } else {
      AppModuleAndroidUtils.openLink(link)
    }
  }

  override suspend fun loadBoard(boardDescriptor: BoardDescriptor) {
    mainScope.launch(Dispatchers.Main.immediate) {
      Logger.d(TAG, "loadBoard($boardDescriptor)")
      boardManager.awaitUntilInitialized()

      val board = boardManager.byBoardDescriptor(boardDescriptor)
        ?: return@launch

      historyNavigationManager.moveNavElementToTop(CatalogDescriptor(boardDescriptor))
      boardManager.updateCurrentBoard(boardDescriptor)

      navigation.title = "/" + boardDescriptor.boardCode + "/"
      navigation.subtitle = board.name ?: ""

      if (!menuBuiltOnce) {
        menuBuiltOnce = true
        buildMenu()
      }

      val catalogDescriptor = CatalogDescriptor.create(
        boardDescriptor.siteName(),
        boardDescriptor.boardCode
      )

      threadLayout.presenter.bindChanDescriptor(catalogDescriptor)
      requireNavController().requireToolbar().updateTitle(navigation)
    }
  }

  override suspend fun showExternalThread(threadToOpenDescriptor: ThreadDescriptor) {
    // no-op, we shouldn't be able to open external threads from catalog because we can't click
    // links when in catalog and stuff.
  }

  override suspend fun openThreadInArchive(threadDescriptor: ThreadDescriptor) {
    // no-op
  }

  override suspend fun showBoard(descriptor: BoardDescriptor, animated: Boolean) {
    mainScope.launch(Dispatchers.Main.immediate) {
      Logger.d(TAG, "showBoard($descriptor, $animated)")

      showBoardInternal(descriptor, animated)
      initialized = true
    }
  }

  override suspend fun setBoard(descriptor: BoardDescriptor, animated: Boolean) {
    mainScope.launch(Dispatchers.Main.immediate) {
      Logger.d(TAG, "setBoard($descriptor, $animated)")

      setBoard(descriptor)
      initialized = true
    }
  }

  // Creates or updates the target ThreadViewController
  // This controller can be in various places depending on the layout
  // We dynamically search for it
  override suspend fun showThread(descriptor: ThreadDescriptor, animated: Boolean) {
    mainScope.launch(Dispatchers.Main.immediate) {
      Logger.d(TAG, "showThread($descriptor, $animated)")

      // The target ThreadViewController is in a split nav
      // (BrowseController -> ToolbarNavigationController -> SplitNavigationController)
      var splitNav: SplitNavigationController? = null

      // The target ThreadViewController is in a slide nav
      // (BrowseController -> SlideController -> ToolbarNavigationController)
      var slideNav: ThreadSlideController? = null
      if (doubleNavigationController is SplitNavigationController) {
        splitNav = doubleNavigationController as SplitNavigationController?
      }

      if (doubleNavigationController is ThreadSlideController) {
        slideNav = doubleNavigationController as ThreadSlideController?
      }

      when {
        splitNav != null -> {
          // Create a threadview inside a toolbarnav in the right part of the split layout
          if (splitNav.getRightController() is StyledToolbarNavigationController) {
            val navigationController = splitNav.getRightController() as StyledToolbarNavigationController
            if (navigationController.top is ViewThreadController) {
              val viewThreadController = navigationController.top as ViewThreadController?
              viewThreadController!!.drawerCallbacks = drawerCallbacks

              viewThreadController.loadThread(descriptor)
              viewThreadController.onShow()
            }
          } else {
            val navigationController = StyledToolbarNavigationController(context)
            splitNav.setRightController(navigationController, animated)
            val viewThreadController = ViewThreadController(context, drawerCallbacks, descriptor)
            navigationController.pushController(viewThreadController, false)
          }
          splitNav.switchToController(false, animated)
        }
        slideNav != null -> {
          // Create a threadview in the right part of the slide nav *without* a toolbar
          if (slideNav.getRightController() is ViewThreadController) {
            (slideNav.getRightController() as ViewThreadController).loadThread(descriptor)
            (slideNav.getRightController() as ViewThreadController).onShow()
          } else {
            val viewThreadController = ViewThreadController(
              context,
              drawerCallbacks,
              descriptor
            )

            slideNav.setRightController(viewThreadController, animated)
          }
          slideNav.switchToController(false, animated)
        }
        else -> {
          // the target ThreadNav must be pushed to the parent nav controller
          // (BrowseController -> ToolbarNavigationController)
          val viewThreadController = ViewThreadController(
            context,
            drawerCallbacks,
            descriptor
          )

          Objects.requireNonNull(navigationController, "navigationController is null")
          navigationController!!.pushController(viewThreadController, animated)
        }
      }

      historyNavigationManager.moveNavElementToTop(descriptor)
      initialized = true
    }
  }

  private suspend fun showBoardInternal(boardDescriptor: BoardDescriptor, animated: Boolean) {
    Logger.d(TAG, "showBoardInternal($boardDescriptor, $animated)")

    // The target ThreadViewController is in a split nav
    // (BrowseController -> ToolbarNavigationController -> SplitNavigationController)
    val splitNav = if (doubleNavigationController is SplitNavigationController) {
      doubleNavigationController as SplitNavigationController?
    } else {
      null
    }

    // The target ThreadViewController is in a slide nav
    // (BrowseController -> SlideController -> ToolbarNavigationController)
    val slideNav = if (doubleNavigationController is ThreadSlideController) {
      doubleNavigationController as ThreadSlideController?
    } else {
      null
    }

    // Do nothing when split navigation is enabled because both controllers are always visible
    // so we don't need to switch between left and right controllers
    if (splitNav == null) {
      if (slideNav != null) {
        slideNav.switchToController(true, animated)
      } else {
        if (navigationController != null) {
          // We wouldn't want to pop BrowseController when opening a board
          if (navigationController!!.top !is BrowseController) {
            navigationController!!.popController(animated)
          }
        }
      }
    }

    setBoard(boardDescriptor)
  }

  override fun onLostFocus(controllerType: ThreadSlideController.ThreadControllerType) {
    super.onLostFocus(controllerType)
    check(controllerType == threadControllerType) { "Unexpected controllerType: $controllerType" }
  }

  override fun onGainedFocus(controllerType: ThreadSlideController.ThreadControllerType) {
    super.onGainedFocus(controllerType)
    check(controllerType == threadControllerType) { "Unexpected controllerType: $controllerType" }

    val searchQuery = threadLayout.presenter.searchQuery
    if (searchQuery != null) {
      toolbar!!.openSearchWithCallback {
        toolbar!!.searchInput(searchQuery)
      }
    }

    if (chanDescriptor != null) {
      historyNavigationManager.moveNavElementToTop(chanDescriptor!!)
    }
  }

  companion object {
    private const val TAG = "BrowseController"

    private const val ACTION_CHANGE_VIEW_MODE = 901
    private const val ACTION_SORT = 902
    private const val ACTION_DEV_MENU = 903
    private const val ACTION_REPLY = 904
    private const val ACTION_OPEN_BROWSER = 905
    private const val ACTION_SHARE = 906
    private const val ACTION_SCROLL_TO_TOP = 907
    private const val ACTION_SCROLL_TO_BOTTOM = 908
    private const val ACTION_OPEN_THREAD_BY_ID = 909
    // TODO(KurobaEx): add action "open is a separate (new?) tab"

    private const val SORT_MODE_BUMP = 1000
    private const val SORT_MODE_REPLY = 1001
    private const val SORT_MODE_IMAGE = 1002
    private const val SORT_MODE_NEWEST = 1003
    private const val SORT_MODE_OLDEST = 1004
    private const val SORT_MODE_MODIFIED = 1005
    private const val SORT_MODE_ACTIVITY = 1006

    private const val DEV_BOOKMARK_EVERY_THREAD = 2000
  }
}
