package com.github.adamantcheese.chan.features.setup

import android.content.Context
import android.view.View
import com.airbnb.epoxy.EpoxyController
import com.airbnb.epoxy.EpoxyRecyclerView
import com.airbnb.epoxy.EpoxyTouchHelper
import com.github.adamantcheese.chan.R
import com.github.adamantcheese.chan.controller.Controller
import com.github.adamantcheese.chan.features.setup.data.BoardsSetupControllerState
import com.github.adamantcheese.chan.features.setup.epoxy.EpoxyBoardViewModel_
import com.github.adamantcheese.chan.features.setup.epoxy.epoxyBoardView
import com.github.adamantcheese.chan.ui.epoxy.epoxyErrorView
import com.github.adamantcheese.chan.ui.epoxy.epoxyLoadingView
import com.github.adamantcheese.chan.ui.epoxy.epoxyTextView
import com.github.adamantcheese.chan.ui.helper.BoardHelper
import com.github.adamantcheese.chan.utils.AndroidUtils
import com.github.adamantcheese.model.data.descriptor.SiteDescriptor
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow

class BoardsSetupController(
  context: Context,
  private val siteDescriptor: SiteDescriptor
) : Controller(context), BoardsSetupView {
  private val presenter = BoardsSetupPresenter(siteDescriptor)
  private val controller = BoardsEpoxyController()

  private lateinit var epoxyRecyclerView: EpoxyRecyclerView

  override fun onCreate() {
    super.onCreate()

    navigation.title = "Configure boards of ${siteDescriptor.siteName}"

    view = AndroidUtils.inflate(context, R.layout.controller_boards_setup)
    epoxyRecyclerView = view.findViewById(R.id.epoxy_recycler_view)
    epoxyRecyclerView.setController(controller)

    mainScope.launch {
      presenter.listenForStateChanges()
        .asFlow()
        .collect { state -> onStateChanged(state) }
    }

    EpoxyTouchHelper
      .initDragging(controller)
      .withRecyclerView(epoxyRecyclerView)
      .forVerticalList()
      .withTarget(EpoxyBoardViewModel_::class.java)
      .andCallbacks(object : EpoxyTouchHelper.DragCallbacks<EpoxyBoardViewModel_>() {
        override fun onModelMoved(
          fromPosition: Int,
          toPosition: Int,
          modelBeingMoved: EpoxyBoardViewModel_,
          itemView: View?
        ) {
          modelBeingMoved.boardDescriptor()?.let { boardDescriptor ->
            presenter.onBoardMoved(boardDescriptor, fromPosition, toPosition)
          }
        }
      })

    EpoxyTouchHelper
      .initSwiping(epoxyRecyclerView)
      .right()
      .withTarget(EpoxyBoardViewModel_::class.java)
      .andCallbacks(object : EpoxyTouchHelper.SwipeCallbacks<EpoxyBoardViewModel_>() {
        override fun onSwipeCompleted(model: EpoxyBoardViewModel_?, itemView: View?, position: Int, direction: Int) {
          model?.boardDescriptor()?.let { boardDescriptor ->
            presenter.onBoardRemoved(boardDescriptor)
          }
        }
      })

    presenter.onCreate(this)
    presenter.updateBoardsFromServerAndDisplayActive()
  }

  override fun onShow() {
    super.onShow()

    presenter.displayActiveBoards()
  }

  override fun onDestroy() {
    super.onDestroy()

    presenter.onDestroy()
  }

  private fun onStateChanged(state: BoardsSetupControllerState) {
    controller.callback = {
      when (state) {
        BoardsSetupControllerState.Loading -> {
          epoxyLoadingView {
            id("boards_setup_loading_view")
          }
        }
        BoardsSetupControllerState.Empty -> {
          epoxyTextView {
            id("boards_setup_empty_text_view")
            message(context.getString(R.string.controller_boards_setup_no_boards))
          }
        }
        is BoardsSetupControllerState.Error -> {
          epoxyErrorView {
            id("boards_setup_error_view")
            errorMessage(state.errorText)
          }
        }
        is BoardsSetupControllerState.Data -> {
          state.boardCellDataList.forEach { boardCellData ->
            epoxyBoardView {
              id("boards_setup_board_view_${boardCellData.boardDescriptor}")
              boardName(BoardHelper.getName(boardCellData.boardDescriptor.boardCode, boardCellData.name))
              boardDescription(boardCellData.description)
            }
          }
        }
      }
    }

    controller.requestModelBuild()
  }

  private class BoardsEpoxyController : EpoxyController() {
    var callback: EpoxyController.() -> Unit = {}

    override fun buildModels() {
      callback(this)
    }
  }

}