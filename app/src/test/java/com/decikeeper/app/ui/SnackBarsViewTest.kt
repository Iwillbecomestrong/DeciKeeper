package com.decikeeper.app.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import com.decikeeper.app.R
import com.decikeeper.app.manager.SnackJarPresentation as Snack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], instrumentedPackages = ["androidx.core.content"], shadows = [SnackDrawableShadow::class])
class SnackBarsViewTest {
    private val context: Context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeciKeeper)

    @Test fun bindsHalfCountAndDoesNotAdvertiseMissingNextSegment() {
        val view = SnackBarsView(context)
        view.render(Snack.Snapshot("today:a", Snack.Quota(5.0, 10.0, 3, 6)), 0)
        assertTrue(view.contentDescription.toString().contains("1.5"))
        assertEquals(View.VISIBLE, view.visibility)
        view.render(null, 0)
        assertEquals(View.GONE, view.visibility)
    }

    @Test fun largeFontNarrowLayoutGrowsRatherThanClippingText() {
        val config = android.content.res.Configuration(context.resources.configuration).apply { fontScale = 1.3f }
        val themed = ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_DeciKeeper)
        val view = SnackBarsView(themed)
        view.render(Snack.Snapshot("a", Snack.Quota(4.0, 10.0, 1, 4),
            nextName = "下一时段有一个比较长的名称", nextQuota = Snack.Quota(8.0, 10.0, 4, 6),
            parcels = listOf(Snack.Parcel("上午", 30, 6, 5000))), 0)
        view.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertTrue(view.measuredHeight > 0)
        fun check(node: View) {
            if (node is android.widget.TextView && node.visibility == View.VISIBLE) {
                val layout = node.layout ?: return
                for (i in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(i))
                assertTrue(layout.height <= node.height - node.paddingTop - node.paddingBottom)
            }
            if (node is android.view.ViewGroup) for (i in 0 until node.childCount) check(node.getChildAt(i))
        }
        check(view)
    }

    @Test fun alarmDecorationsNeverInterceptTouchesAndHideWhenScanningStarts() {
        val root = android.widget.FrameLayout(context)
        val normal = android.widget.ImageView(context).apply { id = R.id.image_alarm_mascot }
        val scan = android.widget.ImageView(context).apply { id = R.id.image_alarm_scan_mascot }
        root.addView(normal); root.addView(scan)
        AlarmMascotPresenter.render(root, cooling = false, connecting = true, completed = false)
        assertEquals(View.VISIBLE, scan.visibility)
        assertEquals(View.GONE, normal.visibility)
        assertFalse(scan.isClickable); assertFalse(scan.isFocusable)
        AlarmMascotPresenter.render(root, cooling = false, connecting = false, completed = false)
        assertEquals(View.GONE, scan.visibility)
        assertEquals(View.GONE, normal.visibility)
    }
}

/** 现有 JVM classpath 不带二进制资源；仅替代图像加载，真实 View 的测量、文本和事件照常执行。 */
@Implements(androidx.core.content.ContextCompat::class, isInAndroidSdk = false)
class SnackDrawableShadow {
    companion object {
        @JvmStatic @Implementation
        fun getDrawable(context: Context, resource: Int): android.graphics.drawable.Drawable =
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
    }
}
