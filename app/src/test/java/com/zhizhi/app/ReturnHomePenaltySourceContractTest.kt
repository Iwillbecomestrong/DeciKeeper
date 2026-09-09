package com.zhizhi.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the real LockWindowManager wiring, not only the pure JVM mirror. */
class ReturnHomePenaltySourceContractTest {
    private val root = File(System.getProperty("user.dir")).let { cwd ->
        if (File(cwd, "app/src/main").exists()) cwd else cwd.parentFile ?: cwd
    }

    private val source: String by lazy {
        File(root, "app/src/main/java/com/zhizhi/app/ui/LockWindowManager.kt").readText()
    }

    @Test
    fun returnHomeButtonIsPenaltyAuthority() {
        assertTrue(
            "RETURN_HOME button handler must record the penalty hit from the explicit user action",
            source.contains("recordReturnHomePenaltyHit(context, now, \"RETURN_HOME_BUTTON\")")
        )
    }

    @Test
    fun successfulOverlayAttachIsNotPenaltyAuthority() {
        val addViewIndex = source.indexOf("targetWM.addView(mLockView, params)")
        val rememberIndex = source.indexOf("rememberLifecycleLock(", startIndex = addViewIndex.coerceAtLeast(0))
        assertTrue("expected addView success block", addViewIndex >= 0 && rememberIndex > addViewIndex)
        val addViewSuccessBlock = source.substring(addViewIndex, rememberIndex)
        assertFalse(
            "successful addView must not increment the repeated-return-home penalty",
            addViewSuccessBlock.contains("recordSuccessfulPenaltyEligibleDisplay(")
        )
    }

    @Test
    fun penaltyCopyNamesTheUserAction() {
        assertTrue(source.contains("30秒内返回桌面"))
        assertFalse(source.contains("30秒内锁屏${'$'}{PENALTY_THRESHOLD}次，触发惩罚"))
    }
}
