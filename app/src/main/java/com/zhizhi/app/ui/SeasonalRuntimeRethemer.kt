package com.zhizhi.app.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.zhizhi.app.R
import java.util.WeakHashMap

/**
 * Applies seasonal tokens to runtime-built or runtime-recolored UI that cannot be
 * expressed purely through XML resources.
 */
object SeasonalRuntimeRethemer {
    private const val LEGACY_BORROW_WARNING = 0xFFFFB74D.toInt()
    private val borrowColorListeners = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()

    fun apply(activity: Activity) {
        if (activity is HelpActivity) {
            applyHelpTheme(activity)
        }
        installBorrowStatusRetheme(activity)
    }

    private fun applyHelpTheme(activity: Activity) {
        val root = activity.window?.decorView ?: return
        val pageBg = resolve(activity, R.attr.zzPageBg, Color.rgb(247, 248, 245))
        val surface = resolve(activity, R.attr.zzSurface, Color.WHITE)
        val text = resolve(activity, R.attr.zzText, Color.rgb(39, 45, 41))
        val accentStrong = resolve(activity, R.attr.zzAccentStrong, Color.rgb(63, 104, 78))
        val muted = resolve(activity, R.attr.zzMuted, Color.rgb(103, 113, 106))
        val line = resolve(activity, R.attr.zzLine, Color.argb(28, 30, 40, 34))

        root.setBackgroundColor(pageBg)
        walk(root) { view ->
            if (view is TextView) {
                val mapped = when (view.currentTextColor) {
                    Color.parseColor("#00838F"),
                    Color.parseColor("#006064"),
                    Color.parseColor("#004D40") -> accentStrong
                    Color.parseColor("#333333"),
                    Color.parseColor("#424242") -> text
                    Color.parseColor("#757575"),
                    Color.parseColor("#9E9E9E"),
                    Color.parseColor("#BDBDBD") -> muted
                    else -> null
                }
                if (mapped != null) view.setTextColor(mapped)
            }

            val bg = view.background
            if (bg is ColorDrawable) {
                when (bg.color) {
                    Color.parseColor("#F5F5F5") -> view.setBackgroundColor(pageBg)
                    Color.parseColor("#FAFAFA") -> view.setBackgroundColor(surface)
                    Color.argb(26, 0, 0, 0) -> view.setBackgroundColor(line)
                }
            }
        }
    }

    /**
     * HomeFragment still changes the borrowed-quota line from Kotlin. Keep the
     * existing business logic untouched and translate only that old orange literal
     * into the currently selected seasonal warning token before the frame is drawn.
     */
    private fun installBorrowStatusRetheme(activity: Activity) {
        val status = activity.findViewById<TextView>(R.id.tv_surplus_borrow_line) ?: return
        if (borrowColorListeners.containsKey(status)) return

        val listener = ViewTreeObserver.OnPreDrawListener {
            if (status.currentTextColor == LEGACY_BORROW_WARNING) {
                status.setTextColor(resolve(activity, R.attr.zzWarning, LEGACY_BORROW_WARNING))
            }
            true
        }
        status.viewTreeObserver.addOnPreDrawListener(listener)
        borrowColorListeners[status] = listener
    }

    private fun walk(view: View, block: (View) -> Unit) {
        block(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), block)
        }
    }

    private fun resolve(activity: Activity, attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (activity.theme.resolveAttribute(attr, value, true)) {
            when {
                value.resourceId != 0 -> runCatching { activity.getColor(value.resourceId) }.getOrDefault(fallback)
                value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> value.data
                else -> fallback
            }
        } else fallback
    }
}
