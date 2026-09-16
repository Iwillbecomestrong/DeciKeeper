package com.decikeeper.app.protection

import com.decikeeper.app.protection.AntiUninstallProtection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 防卡死加固的 JVM 契约：守护无障碍扫描预算、退栈防抖与窗口切换回滚。
 * 这些测试不依赖真机 WindowManager，专门防止后续合并时回退到无界扫描或裸切换。
 */
class AntiDeadlockHardeningContractTest {

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

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("找不到函数签名: $signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("函数体起始大括号缺失: $signature", open >= 0)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        error("函数体大括号未配平: $signature")
    }

    @Test
    fun exitActionDebounceBlocksBurstButPreservesBoundary() {
        assertTrue(AntiUninstallProtection.shouldDispatchExitAction(1_000L, 0L))
        assertFalse(AntiUninstallProtection.shouldDispatchExitAction(1_499L, 1_000L))
        assertTrue(AntiUninstallProtection.shouldDispatchExitAction(1_500L, 1_000L))
    }

    @Test
    fun antiUninstallTraversalHasBoundedBudgetAndFailClosedFallback() {
        val source = source("src/main/java/com/decikeeper/app/protection/AntiUninstallProtection.kt")
        assertTrue(source.contains("NODE_SCAN_MAX_NODES"))
        assertTrue(source.contains("NODE_SCAN_MAX_DURATION_MS"))
        assertTrue(source.contains("class NodeTraversalBudget"))
        assertTrue(source.contains("budget.exhausted"))
        assertTrue(source.contains("TRAVERSAL_LIMIT_EXCEEDED"))
        val detection = functionBody(source, "private fun detectUninstallAttemptSample(")
        assertTrue(
            "无障碍列表豁免必须先于超限 fail-closed",
            detection.indexOf("shouldSkipAccessibilitySettings") < detection.indexOf("budget.exhausted")
        )
        val collect = functionBody(source, "private fun collectAllText(")
        assertTrue(collect.contains("budget.tryVisit()"))
        assertTrue(collect.contains("budget)"))
    }

    @Test
    fun coreServiceIdentifierTraversalHasDepthNodeAndTimeFuse() {
        val source = source("src/main/java/com/decikeeper/app/service/CoreService.kt")
        assertTrue(source.contains("NODE_IDENTIFIER_MAX_NODES"))
        assertTrue(source.contains("NODE_IDENTIFIER_MAX_DEPTH"))
        assertTrue(source.contains("NODE_IDENTIFIER_MAX_DURATION_MS"))
        assertTrue(source.contains("class NodeIdentifierBudget"))
        assertTrue(functionBody(source, "private fun hasNodeIdentifier(").contains("budget.tryVisit(depth)"))
    }

    @Test
    fun hiddenAlarmRecoveryUsesAtomicAttach() {
        val source = source("src/main/java/com/decikeeper/app/ui/LockAlarmOverlay.kt")
        val body = functionBody(source, "fun showAlarmOverlayIfHidden()")
        assertTrue(body.contains("attachAlarmViewAtomically("))
        assertFalse(body.contains("targetWM.addView("))
        assertFalse(body.contains("parent as? android.view.ViewManager"))
    }

    @Test
    fun lockReassertHasWindowManagerRollback() {
        val source = source("src/main/java/com/decikeeper/app/ui/LockWindowManager.kt")
        val body = functionBody(source, "private fun reassertAttachedLockVisibility(reason: String)")
        assertTrue(body.contains("rollbackSucceeded"))
        assertTrue(body.contains("REASSERT_ROLLED_BACK"))
        assertTrue(body.contains("if (lockView.parent == null)"))
    }

    @Test
    fun packageInstallerFlowExemptsInstallationAndDistinguishesUninstall() {
        // 验证安装器更新流程必须放行，且不能把安装当成卸载
        assertTrue(AntiUninstallProtection.isInstallationFlow("com.android.packageinstaller", "要安装此应用的更新吗？决定守护 取消 安装"))
        assertTrue(AntiUninstallProtection.isInstallationFlow("com.google.android.packageinstaller", "Do you want to update this app? DeciKeeper Cancel Install"))
        // 如果包含卸载关键词，则绝不能放行为安装流程
        assertFalse(AntiUninstallProtection.isInstallationFlow("com.android.packageinstaller", "要卸载此应用吗？决定守护 取消 确定"))
        assertFalse(AntiUninstallProtection.isInstallationFlow("com.android.settings", "安装"))
    }
}
