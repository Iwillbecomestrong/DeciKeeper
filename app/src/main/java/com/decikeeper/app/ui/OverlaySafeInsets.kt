package com.decikeeper.app.ui

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/**
 * Target view and edge configurations for safe insets padding.
 */
data class InsetPaddingTarget(
    val view: View,
    val applyLeft: Boolean = false,
    val applyTop: Boolean = false,
    val applyRight: Boolean = false,
    val applyBottom: Boolean = false
)

/**
 * Resolves safe insets combining system bars and display cutout, taking the maximum of each edge.
 */
fun resolveOverlaySafeInsets(
    systemBars: Insets,
    displayCutout: Insets
): Insets {
    return Insets.of(
        max(systemBars.left, displayCutout.left),
        max(systemBars.top, displayCutout.top),
        max(systemBars.right, displayCutout.right),
        max(systemBars.bottom, displayCutout.bottom)
    )
}

/**
 * Installs window insets listener on root view to dynamically apply safe margins to target views.
 * Preserves initial paddings to avoid padding accumulation across multiple insets dispatches.
 */
fun installOverlaySafeInsets(
    root: View,
    vararg targets: InsetPaddingTarget
) {
    val initialPaddings = targets.map { target ->
        target to intArrayOf(
            target.view.paddingLeft,
            target.view.paddingTop,
            target.view.paddingRight,
            target.view.paddingBottom
        )
    }

    ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
        val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val safe = resolveOverlaySafeInsets(systemBars, cutout)

        for ((target, initial) in initialPaddings) {
            val left = initial[0] + if (target.applyLeft) safe.left else 0
            val top = initial[1] + if (target.applyTop) safe.top else 0
            val right = initial[2] + if (target.applyRight) safe.right else 0
            val bottom = initial[3] + if (target.applyBottom) safe.bottom else 0
            target.view.setPadding(left, top, right, bottom)
        }
        windowInsets
    }

    ViewCompat.requestApplyInsets(root)
}
