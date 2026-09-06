package com.zhizhi.app.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class ThemeSummaryView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatTextView(context, attrs) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        text = ThemeManager.currentLabel(context) + "  ›"
    }
}
