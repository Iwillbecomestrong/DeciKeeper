package com.decikeeper.app.manager

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

        assertTrue(lockWindow.contains("recordReturnHomePenaltyHit(context, System.currentTimeMillis(), \"RETURN_HOME_BUTTON\")"))
        assertFalse(lockWindow.contains("recordSuccessfulPenaltyEligibleDisplay"))
        // 文案与规则收口在 FrequentReturnDeduction；LockWindowManager 只引用，不再硬编码「惩罚」
        assertTrue(lockWindow.contains("FrequentReturnDeduction.receiptText("))
        assertTrue(lockWindow.contains("FrequentReturnDeduction.hintText("))
        assertTrue(lockWindow.contains("FrequentReturnDeduction.deductSeconds(isAnyCooling)"))
        assertFalse(lockWindow.contains("触发惩罚"))
        assertFalse(lockWindow.contains("30秒内锁屏"))

        // 第5次点击：回执当场展示，确认后才回桌面；不得再等下一次锁屏 attach 才显示
        val clickStart = lockWindow.indexOf("btnBackToHome?.setSafeOnClickListener")
        val clickEnd = lockWindow.indexOf("纯粹三按钮架构点击逻辑", clickStart)
        assertTrue(clickStart >= 0 && clickEnd > clickStart)
        val clickBlock = lockWindow.substring(clickStart, clickEnd)
        assertTrue(clickBlock.contains("penaltyOverlay?.visibility = View.VISIBLE"))
        assertTrue(clickBlock.contains("mPendingReturnHomeAction"))
        assertFalse(
            "pending receipt must not be deferred to the next lock attach",
            lockWindow.contains("penaltyMessage?.text = mPendingPenaltyMessage")
        )

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
