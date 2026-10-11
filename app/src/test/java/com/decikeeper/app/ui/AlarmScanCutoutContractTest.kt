package com.decikeeper.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 扫码取景模式的契约：覆盖层保持可触摸，取景框自身是不透明的 CameraX 画面。
 *
 * 关联真机缺陷：旧实现把整层设为 alpha=0 + FLAG_NOT_TOUCHABLE（"穿透"），
 * 结果扫码期间既盖不住也拦不住系统弹层——长按电源键会露出 vivo 语音助手，
 * 关机菜单还能被直接点击。
 *
 * 分屏回归：严禁依赖下方 AlarmActivity 的窗口透过透明开孔显示相机，
 * 否则在应急白名单的分屏场景中会露出另一半应用的内容。
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
    private val activitySource by lazy {
        source("src/main/java/com/decikeeper/app/ui/AlarmActivity.kt")
    }
    private val lockManagerSource by lazy {
        source("src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")
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
        // 根容器/扫码容器透明由挡板填充；取景框必须独立不透明，不能透出分屏应用。
        assertTrue(overlaySource.contains("root?.setBackgroundColor(Color.TRANSPARENT)"))
        assertTrue(overlaySource.contains("scanLayout?.setBackgroundColor(Color.TRANSPARENT)"))
        // 挑战内容不能和扫码布局重叠
        assertTrue(overlaySource.contains("content?.visibility = View.GONE"))
        // 退出时必须原样还原背景
        assertTrue(overlaySource.contains("savedRootBackground"))
        assertTrue(overlaySource.contains("savedScanBackground"))
    }

    @Test
    fun layoutProvidesOpaqueLetterboxAndOwnPreviewInsteadOfTransparentHole() {
        assertTrue("取景开孔必须存在", layout.contains("@+id/scan_viewfinder_hole"))
        assertTrue("四周不透明挡板必须存在",
            layout.contains("@+id/scan_letterbox_top") &&
                layout.contains("@+id/scan_letterbox_bottom") &&
                layout.contains("@+id/scan_letterbox_left") &&
                layout.contains("@+id/scan_letterbox_right"))
        // 取景框内置 CameraX PreviewView；即使相机还没启动，也不能看到后面的分屏应用。
        val holeIdx = layout.indexOf("@+id/scan_viewfinder_hole")
        val holeBlock = layout.substring(holeIdx, minOf(holeIdx + 1250, layout.length))
        assertTrue("取景框必须有不透明背景", holeBlock.contains("android:background=\"#000000\""))
        assertTrue("必须内嵌独立 PreviewView", holeBlock.contains("@+id/preview_alarm_overlay"))
        assertTrue("预览失败时必须有清晰提示", holeBlock.contains("@+id/tv_alarm_scan_preview_status"))
        assertFalse("取景框不允许透明", holeBlock.contains("android:background=\"@android:color/transparent\""))
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
    fun overlayOldSurfaceViewScannerStaysHidden() {
        val idx = layout.indexOf("@+id/barcode_alarm_scanner")
        val block = layout.substring(idx, minOf(idx + 320, layout.length))
        assertTrue(
            "旧 SurfaceView 扫码器不应显示；取景器由 CameraX TextureView 负责",
            block.contains("android:visibility=\"gone\"")
        )
    }

    @Test
    fun cameraPreviewUsesTextureViewAndDoesNotExposeSplitScreenTask() {
        val manager = source("src/main/java/com/decikeeper/app/ui/CameraXScanManager.kt")
        assertTrue(manager.contains("PreviewView.ImplementationMode.COMPATIBLE"))
        assertTrue("相机预览应挂在覆盖层，而不是在分屏 Activity 后面透出",
            activitySource.contains("findViewById<PreviewView>(R.id.preview_alarm_overlay)"))
        assertTrue("仅在画面真正可见时允许扫码通过",
            activitySource.contains("if (!scanPreviewStreaming)"))
        assertTrue("相机预览异常需要可见提示",
            activitySource.contains("SCAN_OVERLAY_PREVIEW_TIMEOUT"))
        assertTrue("退出扫码必须移除预览观察者",
            activitySource.contains("clearScanPreviewObservation()"))
    }

    @Test
    fun scanCutoutHidesLockScreenToPreventOccludingCamera() {
        assertTrue(
            "开启开孔必须隐藏普通锁机界面，退出开孔必须恢复普通锁机界面",
            overlaySource.contains("LockWindowManager.setLockScreenVisibleForAlarmScan(!enable)")
        )
        assertTrue(
            "关闭闹钟时必须确保普通锁机界面恢复",
            overlaySource.contains("LockWindowManager.setLockScreenVisibleForAlarmScan(true)")
        )
        assertTrue(
            "LockWindowManager 必须提供 setLockScreenVisibleForAlarmScan",
            lockManagerSource.contains("fun setLockScreenVisibleForAlarmScan(visible: Boolean)")
        )
        val lockManagerMethod = lockManagerSource.substring(
            lockManagerSource.indexOf("fun setLockScreenVisibleForAlarmScan(visible: Boolean)"),
            lockManagerSource.indexOf("fun onAlarmScanResult(success: Boolean)")
        )
        assertTrue(
            "隐藏时必须设为 GONE，恢复时设为 VISIBLE",
            lockManagerMethod.contains("view.visibility = if (visible) View.VISIBLE else View.GONE")
        )
        assertTrue(
            "隐藏时必须设为 alpha=0.0，恢复时设为 1.0",
            lockManagerMethod.contains("view.alpha = if (visible) 1.0f else 0.0f")
        )
    }

    @Test
    fun cancelScanInLockScreenModeDoesNotKillActivity() {
        val cancelMethod = activitySource.substring(
            activitySource.indexOf("override fun onScanCancel()"),
            activitySource.indexOf("private fun finishScanAndRestoreOverlay()")
        )
        assertTrue(
            "取消扫码时必须仅在非锁屏模式下才 finishAndRemoveTask，锁屏下需保持 Activity 存活展示挑战",
            cancelMethod.contains("if (!isLockedScreenMode)") &&
                cancelMethod.contains("finishAndRemoveTask()")
        )
        assertFalse(
            "不得在取消扫码时无条件杀死 Activity",
            cancelMethod.replace("if (!isLockedScreenMode)", "").trim().startsWith("finishAndRemoveTask()")
        )
    }

    @Test
    fun cancelButtonCallsCancelAlarmScanAndRestoresChallenge() {
        assertTrue(
            "覆盖层返回挑战按钮必须调用 cancelAlarmScan()",
            overlaySource.contains("cancelAlarmScan()")
        )
        val cancelMethod = overlaySource.substring(
            overlaySource.indexOf("fun cancelAlarmScan()"),
            overlaySource.indexOf("fun detachAlarmController(")
        )
        assertTrue(
            "cancelAlarmScan 必须退出开孔模式并恢复覆盖层与题目",
            cancelMethod.contains("setAlarmOverlayPassthrough(false)") &&
                cancelMethod.contains("showAlarmOverlayIfHidden()") &&
                cancelMethod.contains("mAlarmController?.onScanCancel()")
        )
    }

    @Test
    fun unlockedModeScanKeepsOverlayAttached() {
        val alarmManagerSource = source("src/main/java/com/decikeeper/app/manager/AlarmWindowManager.kt")
        val noActivityIdx = alarmManagerSource.indexOf("private object NoActivityAlarmController")
        assertTrue("NoActivityAlarmController 必须存在", noActivityIdx >= 0)
        val noActivityBlock = alarmManagerSource.substring(noActivityIdx)
        assertFalse(
            "非锁屏扫码不得移除覆盖层，必须保持覆盖层在窗口栈中以统一开孔视觉",
            noActivityBlock.contains("hideAlarmOverlayForPermission")
        )
        assertTrue(
            "非锁屏扫码必须传 start_scan=true",
            noActivityBlock.contains("putExtra(\"start_scan\", true)")
        )
    }
}
