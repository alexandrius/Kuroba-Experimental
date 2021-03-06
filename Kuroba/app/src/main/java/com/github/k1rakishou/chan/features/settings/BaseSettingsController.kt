package com.github.k1rakishou.chan.features.settings

import android.content.Context
import com.github.k1rakishou.chan.controller.Controller
import com.github.k1rakishou.chan.core.helper.DialogFactory
import com.github.k1rakishou.chan.features.settings.setting.InputSettingV2
import com.github.k1rakishou.chan.features.settings.setting.ListSettingV2
import com.github.k1rakishou.chan.ui.controller.FloatingListMenuController
import com.github.k1rakishou.chan.ui.misc.ConstraintLayoutBiasPair
import com.github.k1rakishou.chan.ui.view.floating_menu.CheckableFloatingListMenuItem
import com.github.k1rakishou.common.exhaustive
import com.github.k1rakishou.core_logger.Logger
import javax.inject.Inject

abstract class BaseSettingsController(
  context: Context
) : Controller(context) {

  @Inject
  lateinit var dialogFactory: DialogFactory

  protected fun showListDialog(settingV2: ListSettingV2<*>, onItemClicked: (Any?) -> Unit) {
    val items = settingV2.items.mapIndexed { index, item ->
      return@mapIndexed CheckableFloatingListMenuItem(
        key = index,
        name = settingV2.itemNameMapper(item),
        value = item,
        isCurrentlySelected = settingV2.isCurrent(item)
      )
    }

    val controller = FloatingListMenuController(
      context = context,
      items = items,
      constraintLayoutBiasPair = ConstraintLayoutBiasPair.Center,
      itemClickListener = { clickedItem ->
        settingV2.updateSetting(clickedItem.value)
        onItemClicked(clickedItem.value)
      })

    navigationController!!.presentController(
      controller,
      true
    )
  }

  protected fun showInputDialog(
    inputSettingV2: InputSettingV2<*>,
    rebuildScreenFunc: (Any?) -> Unit
  ) {
    val inputType = inputSettingV2.inputType
    if (inputType == null) {
      Logger.e(TAG, "Bad input type: ${inputType}")
      return
    }

    dialogFactory.createSimpleDialogWithInput(
      context = context,
      defaultValue = inputSettingV2.getCurrent().toString(),
      inputType = inputType,
      titleText = inputSettingV2.topDescription,
      onValueEntered = { input ->
        onInputValueEntered(inputSettingV2, input, rebuildScreenFunc)
      }
    )
  }

  @Suppress("FoldInitializerAndIfToElvis")
  protected fun onInputValueEntered(
    inputSettingV2: InputSettingV2<*>,
    input: String,
    rebuildScreenFunc: (Any?) -> Unit
  ) {
    when (inputSettingV2.inputType) {
      DialogFactory.DialogInputType.String -> {
        val text = if (input.isNotEmpty()) {
          input
        } else {
          inputSettingV2.getDefault()?.toString()
        }

        if (text == null) {
          return
        }

        inputSettingV2.updateSetting(text)
      }
      DialogFactory.DialogInputType.Integer -> {
        val integer = if (input.isNotEmpty()) {
          input.toIntOrNull()
        } else {
          inputSettingV2.getDefault() as? Int
        }

        if (integer == null) {
          return
        }

        inputSettingV2.updateSetting(integer)
      }
      null -> throw IllegalStateException("InputType is null")
    }.exhaustive

    rebuildScreenFunc(inputSettingV2.getCurrent())
  }

  companion object {
    private const val TAG = "BaseSettingsController"
  }

}