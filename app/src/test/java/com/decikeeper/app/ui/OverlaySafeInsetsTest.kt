package com.decikeeper.app.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OverlaySafeInsetsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun resolveOverlaySafeInsets_takesMaxOfSystemBarsAndDisplayCutout() {
        val systemBars = Insets.of(0, 24, 0, 48)
        val cutout = Insets.of(0, 80, 0, 0)
        val resolved = resolveOverlaySafeInsets(systemBars, cutout)

        assertEquals(0, resolved.left)
        assertEquals(80, resolved.top)
        assertEquals(0, resolved.right)
        assertEquals(48, resolved.bottom)
    }

    @Test
    fun resolveOverlaySafeInsets_horizontalCutout() {
        val systemBars = Insets.of(10, 20, 10, 20)
        val cutout = Insets.of(30, 0, 25, 0)
        val resolved = resolveOverlaySafeInsets(systemBars, cutout)

        assertEquals(30, resolved.left)
        assertEquals(20, resolved.top)
        assertEquals(25, resolved.right)
        assertEquals(20, resolved.bottom)
    }

    @Test
    fun resolveOverlaySafeInsets_zeroCutout_retainsSystemBars() {
        val systemBars = Insets.of(15, 25, 35, 45)
        val cutout = Insets.of(0, 0, 0, 0)
        val resolved = resolveOverlaySafeInsets(systemBars, cutout)

        assertEquals(systemBars, resolved)
    }

    @Test
    fun installOverlaySafeInsets_appliesSafePaddingAndPreventsAccumulation() {
        val root = FrameLayout(context)
        val child = View(context).apply {
            setPadding(10, 20, 30, 40)
        }
        root.addView(child)

        installOverlaySafeInsets(
            root,
            InsetPaddingTarget(child, applyLeft = true, applyTop = true, applyRight = true, applyBottom = true)
        )

        val windowInsets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 25, 0, 50))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 60, 0, 0))
            .build()

        ViewCompat.dispatchApplyWindowInsets(root, windowInsets)

        // Initial (10, 20, 30, 40) + safe (0, 60, 0, 50) = (10, 80, 30, 90)
        assertEquals(10, child.paddingLeft)
        assertEquals(80, child.paddingTop)
        assertEquals(30, child.paddingRight)
        assertEquals(90, child.paddingBottom)

        // Dispatching again must NOT accumulate padding
        ViewCompat.dispatchApplyWindowInsets(root, windowInsets)
        assertEquals(10, child.paddingLeft)
        assertEquals(80, child.paddingTop)
        assertEquals(30, child.paddingRight)
        assertEquals(90, child.paddingBottom)
    }

    @Test
    fun installOverlaySafeInsets_selectiveEdges() {
        val root = FrameLayout(context)
        val topBar = View(context).apply { setPadding(5, 5, 5, 5) }
        val bottomBar = View(context).apply { setPadding(6, 6, 6, 6) }
        root.addView(topBar)
        root.addView(bottomBar)

        installOverlaySafeInsets(
            root,
            InsetPaddingTarget(topBar, applyLeft = true, applyTop = true, applyRight = true, applyBottom = false),
            InsetPaddingTarget(bottomBar, applyLeft = true, applyTop = false, applyRight = true, applyBottom = true)
        )

        val windowInsets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(12, 30, 14, 40))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 0, 0, 0))
            .build()

        ViewCompat.dispatchApplyWindowInsets(root, windowInsets)

        assertEquals(5 + 12, topBar.paddingLeft)
        assertEquals(5 + 30, topBar.paddingTop)
        assertEquals(5 + 14, topBar.paddingRight)
        assertEquals(5, topBar.paddingBottom)

        assertEquals(6 + 12, bottomBar.paddingLeft)
        assertEquals(6, bottomBar.paddingTop)
        assertEquals(6 + 14, bottomBar.paddingRight)
        assertEquals(6 + 40, bottomBar.paddingBottom)
    }
}
