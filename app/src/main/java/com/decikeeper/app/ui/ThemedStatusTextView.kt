package com.decikeeper.app.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.decikeeper.app.R

/**
 * TextView used by runtime status lines that historically changed to a hard-coded
 * warning orange from Kotlin. It preserves normal XML/theme colors, but maps the
 * legacy borrowed-quota orange to the current seasonal warning token.
 */
class ThemedStatusTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    companion object {
        private const val LEGACY_WARNING = 0xFFFFB74D.toInt()
    }

    override fun setTextColor(color: Int) {
        if (color == LEGACY_WARNING) {
            super.setTextColor(resolveThemeColor(R.attr.zzWarning, LEGACY_WARNING))
        } else {
            super.setTextColor(color)
        }
    }

    private fun resolveThemeColor(attr: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            when {
                value.resourceId != 0 -> runCatching { context.getColor(value.resourceId) }.getOrDefault(fallback)
                value.type in android.util.TypedValue.TYPE_FIRST_COLOR_INT..android.util.TypedValue.TYPE_LAST_COLOR_INT -> value.data
                else -> fallback
            }
        } else {
            fallback
        }
    }
}
