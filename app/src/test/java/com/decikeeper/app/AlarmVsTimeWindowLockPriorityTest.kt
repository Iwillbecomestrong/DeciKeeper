package com.decikeeper.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmVsTimeWindowLockPriorityTest {

    @Test
    fun lockScreenIsSuppressedWhenAlarmIsActive() {
        val lockWmSource = source("app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")

        assertTrue(
            "LockWindowManager 必须定义 isAlarmActiveForLockSuppression() 判断闹钟是否活跃",
            lockWmSource.contains("fun isAlarmActiveForLockSuppression(): Boolean")
        )
        assertTrue(
            "showLockScreen 挂载 lockView 时，若闹钟活跃必须压制普通锁屏（设为 GONE, alpha 0.0, NOT_TOUCHABLE, NOT_FOCUSABLE）",
            lockWmSource.contains("val shouldSuppressForAlarm = isAlarmActiveForLockSuppression()") &&
                lockWmSource.contains("mLockView?.visibility = View.GONE") &&
                lockWmSource.contains("params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE")
        )
        assertTrue(
            "showLockScreen 在已有锁屏触发重排时，若闹钟活跃必须保持压制并直接返回，禁止覆盖闹钟挑战",
            lockWmSource.contains("isAlarmActiveForLockSuppression()") &&
                lockWmSource.contains("suppressLockScreenForAlarm()")
        )
    }

    @Test
    fun alarmOverlaySuppressesNormalLockOnShowAndReassert() {
        val alarmOverlaySource = source("app/src/main/java/com/decikeeper/app/ui/LockAlarmOverlay.kt")

        assertTrue(
            "LockAlarmOverlay showAlarmOverlay 必须在展示挑战时调用 LockWindowManager.suppressLockScreenForAlarm()",
            alarmOverlaySource.contains("LockWindowManager.suppressLockScreenForAlarm()")
        )
        assertTrue(
            "LockAlarmOverlay dismissAlarmOverlay 必须在结束时调用 LockWindowManager.resumeLockScreenAfterAlarmDismiss()",
            alarmOverlaySource.contains("LockWindowManager.resumeLockScreenAfterAlarmDismiss()")
        )
    }

    @Test
    fun lockScreenResumesAfterAlarmDismissal() {
        val lockWmSource = source("app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")

        assertTrue(
            "LockWindowManager 必须提供 suppressLockScreenForAlarm 方法",
            lockWmSource.contains("fun suppressLockScreenForAlarm()")
        )
        assertTrue(
            "LockWindowManager 必须提供 resumeLockScreenAfterAlarmDismiss 方法，并在闹钟关闭后恢复锁屏可见性",
            lockWmSource.contains("fun resumeLockScreenAfterAlarmDismiss()") &&
                lockWmSource.contains("isLockScreenSuppressedByAlarm = false") &&
                lockWmSource.contains("view.visibility = View.VISIBLE")
        )
    }

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, path),
            File(cwd.parentFile, "IronWill-alarm-priority/$path"),
            File("G:/IronWIl0329/IronWill-alarm-priority", path)
        )
        for (candidate in candidates) {
            if (candidate.exists()) return candidate.readText()
        }
        throw AssertionError("Could not find $path from ${cwd.absolutePath}")
    }
}
