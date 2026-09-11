package com.decikeeper.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnHomePenaltySourceContractTest {
    private fun source(path: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, path) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $path")

    @Test
    fun onlyExplicitReturnHomeActionOwnsPenaltyCounting() {
        val lockWindow = source("app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")

        assertTrue(lockWindow.contains("recordReturnHomePenaltyHit(context, now, \"RETURN_HOME_BUTTON\")"))
        assertFalse(lockWindow.contains("recordSuccessfulPenaltyEligibleDisplay"))
        assertTrue(lockWindow.contains("30秒内返回桌面\${PENALTY_THRESHOLD}次，触发惩罚"))
        assertTrue(lockWindow.contains("30秒内返回桌面 \${mPenaltyHitCount}/\${PENALTY_THRESHOLD} 次"))
        assertFalse(lockWindow.contains("30秒内锁屏\${PENALTY_THRESHOLD}次，触发惩罚"))
        assertFalse(lockWindow.contains("30秒内锁屏 \${mPenaltyHitCount}/\${PENALTY_THRESHOLD} 次"))

        val attachStart = lockWindow.indexOf("targetWM.addView(mLockView, params)")
        val attachEnd = lockWindow.indexOf("rememberLifecycleLock(", attachStart)
        assertTrue("addView success block must be present", attachStart >= 0)
        assertTrue("lock lifecycle record must follow addView success", attachEnd > attachStart)
        assertFalse(
            "overlay attachment must not own return-home penalty counting",
            lockWindow.substring(attachStart, attachEnd).contains("recordReturnHomePenaltyHit")
        )
    }
}
