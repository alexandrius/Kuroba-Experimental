package com.github.k1rakishou.chan.features.search.epoxy

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.airbnb.epoxy.CallbackProp
import com.airbnb.epoxy.ModelProp
import com.airbnb.epoxy.ModelView
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.ui.theme.widget.ColorizableButton

@ModelView(autoLayout = ModelView.Size.MATCH_WIDTH_WRAP_HEIGHT)
class EpoxySearchButtonView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
  private val searchButton: ColorizableButton

  private var currentQuery: String? = null

  init {
    inflate(context, R.layout.epoxy_search_button_view, this)

    searchButton = findViewById(R.id.search_button)
  }

  @CallbackProp
  fun setOnButtonClickListener(listener: ((String?) -> Unit)?) {
    if (listener == null) {
      searchButton.setOnClickListener(null)
      return
    }

    searchButton.setOnClickListener { listener.invoke(currentQuery) }
  }

  @ModelProp
  fun setCurrentQuery(query: String) {
    currentQuery = query
  }

}