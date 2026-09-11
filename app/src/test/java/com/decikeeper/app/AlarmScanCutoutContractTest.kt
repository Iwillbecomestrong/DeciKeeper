package com.decikeeper.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 扫码取景模式的契约：覆盖层必须保持**不透明且可触摸**，只在中间留取景开孔。
 *
 * 关联真机缺陷：旧实现把整层设为 alpha=0 + FLAG_NOT_TOUCHABLE（"穿透"），
 * 结果扫码期间既盖不住也拦不住系统弹层——长按电源键会露出 vivo 语音助手，
 * 关机菜单还能被直接点击。
 *
 * 注意：覆盖层窗口渲染不了 SurfaceView，所以开孔里的画面来自下方 AlarmActivity
 * 的相机预览，覆盖层自带的 DecoratedBarcodeView 必须保持不可见。
 */
class AlarmScanCutoutContractTest {

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(cwd, path),
            File(cwd, "app/$path"),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, "app/$path")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $path from ${cwd.absolutePath}")
    }

    private val overlaySource by lazy {
        source("src/main/java/com/decikeeper/app/ui/LockAlarmOverlay.kt")
    }
    private val layout by lazy {
        source("src/main/res/layout/window_alarm_overlay.xml")
    }

    @Test
    fun scanModeNeverMakesOverlayTransparent() {
        val start = overlaySource.indexOf("fun setAlarmOverlayPassthrough(enable: Boolean)")
        assertTrue("找不到 setAlarmOverlayPassthrough", start >= 0)
        val body = overlaySource.substring(start, overlaySource.indexOf("private fun applyScanCutoutLayers"))
        assertFalse(
            "扫码期间不得再把整层设为透明——那会让系统弹层直接露出来",
            body.contains("alpha = 0.0f")
        )
        assertFalse(
            "扫码期间不得再把覆盖层设为不可触摸——那等于把关机菜单的操作权交还用户",
            body.contains("FLAG_NOT_TOUCHABLE or")
        )
        assertTrue("必须显式恢复 alpha=1.0", body.contains("alpha = 1.0f"))
    }

    @Test
    fun scanCutoutLayersAreAppliedAndReverted() {
        assertTrue(overlaySource.contains("applyScanCutoutLayers(view, enable)"))
        assertTrue(overlaySource.contains("SCAN_CUTOUT_ENABLED"))
        assertTrue(overlaySource.contains("SCAN_CUTOUT_DISABLED"))
        // 根容器与扫码容器必须转透明，否则会挡住开孔
        assertTrue(overlaySource.contains("root?.setBackgroundColor(Color.TRANSPARENT)"))
        assertTrue(overlaySource.contains("scanLayout?.setBackgroundColor(Color.TRANSPARENT)"))
        // 挑战内容必须隐藏，否则会从开孔透出来
        assertTrue(overlaySource.contains("content?.visibility = View.GONE"))
        // 退出时必须原样还原背景
        assertTrue(overlaySource.contains("savedRootBackground"))
        assertTrue(overlaySource.contains("savedScanBackground"))
    }

    @Test
    fun layoutProvidesOpaqueLetterboxAroundViewfinderHole() {
        assertTrue("取景开孔必须存在", layout.contains("@+id/scan_viewfinder_hole"))
        assertTrue("四周不透明挡板必须存在",
            layout.contains("@+id/scan_letterbox_top") &&
                layout.contains("@+id/scan_letterbox_bottom") &&
                layout.contains("@+id/scan_letterbox_left") &&
                layout.contains("@+id/scan_letterbox_right"))
        // 开孔本身必须透明，否则相机预览透不出来
        val holeIdx = layout.indexOf("@+id/scan_viewfinder_hole")
        val holeBlock = layout.substring(holeIdx, minOf(holeIdx + 320, layout.length))
        assertTrue("开孔背景必须透明", holeBlock.contains("@android:color/transparent"))
    }

    @Test
    fun scanContainerItselfIsTransparent() {
        val idx = layout.indexOf("@+id/layout_alarm_scan")
        val block = layout.substring(idx, minOf(idx + 360, layout.length))
        assertTrue(
            "扫码容器背景必须透明，否则会盖住取景开孔",
            block.contains("android:background=\"@android:color/transparent\"")
        )
    }

    @Test
    fun overlayOwnScannerStaysHiddenBecauseSurfaceViewCannotRenderInOverlay() {
        val idx = layout.indexOf("@+id/barcode_alarm_scanner")
        val block = layout.substring(idx, minOf(idx + 320, layout.length))
        assertTrue(
            "覆盖层自带的 DecoratedBarcodeView 必须保持 gone——覆盖层窗口渲染不了 SurfaceView",
            block.contains("android:visibility=\"gone\"")
        )
    }
}
