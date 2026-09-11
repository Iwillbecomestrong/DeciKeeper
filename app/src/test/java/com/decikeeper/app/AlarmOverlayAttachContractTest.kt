package com.decikeeper.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 闹钟覆盖层挂载路径的源文本契约。
 *
 * 关联真机缺陷：覆盖层从 inline 切到窗口模式时 `removeView` 与 `addView` 不是原子操作，
 * addView 失败后异常被吞掉且没有回滚，view 变成无父容器的游离态；此后完成态渲染不显示，
 * 用户白屏且无法关闭闹钟。
 *
 * 本测试守护修复不被回退：只要挂载路径重新出现裸的 remove/add 组合，或完成态渲染绕过
 * 收殓守卫，测试立刻失败。
 */
class AlarmOverlayAttachContractTest {

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

    /** 按大括号配平截取函数体，避免用行号锚点（行号会随重构失效）。 */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("找不到函数签名: $signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("函数体起始大括号缺失: $signature", open >= 0)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        error("函数体大括号未配平: $signature")
    }

    private val overlaySource by lazy {
        source("src/main/java/com/decikeeper/app/ui/LockAlarmOverlay.kt")
    }

    private val coreServiceSource by lazy {
        source("src/main/java/com/decikeeper/app/service/CoreService.kt")
    }

    @Test
    fun reassertUsesAtomicAttachInsteadOfRawRemoveAdd() {
        val body = functionBody(overlaySource, "fun reassertAlarmOverlayOverSystemDialog(reason: String)")

        // 裸的「先摘后挂」正是游离视图的来源
        assertFalse(
            "reassert 不得再出现裸的 a11yWm.addView(...)——必须走 attachAlarmViewAtomically",
            body.contains("a11yWm.addView(")
        )
        assertFalse(
            "reassert 不得再直接调用 wm.removeView(...)",
            body.contains("wm.removeView(")
        )
        assertTrue(
            "reassert 必须通过 attachAlarmViewAtomically 切换窗口",
            body.contains("attachAlarmViewAtomically(")
        )
    }

    @Test
    fun ensureWindowModeUsesAtomicAttach() {
        val body = functionBody(overlaySource, "fun ensureAlarmOverlayInWindowMode(): Boolean")

        assertTrue(body.contains("attachAlarmViewAtomically("))
        assertFalse("不得再裸调 wm.addView(view, params)", body.contains("wm.addView(view, params)"))
        assertTrue(body.contains("AttachAttemptOutcome.SWITCHED"))
    }

    @Test
    fun atomicAttachReportsEveryOutcome() {
        assertTrue(
            "必须记录回滚",
            overlaySource.contains("OVERLAY_ATTACH_ROLLED_BACK")
        )
        assertTrue(
            "必须记录需要重建",
            overlaySource.contains("OVERLAY_DETACHED_NEEDS_REBUILD")
        )
        assertTrue(
            "必须记录挂载失败",
            overlaySource.contains("OVERLAY_ATTACH_FAILED")
        )
    }

    @Test
    fun completedStateRenderingGuardsAgainstDetachedView() {
        // 完成态渲染的两个入口都必须先收殓游离视图
        assertTrue(
            "onAnyChallengePassed 必须经 resolveRenderableAlarmView 取视图",
            functionBody(overlaySource, "internal fun onAnyChallengePassed()")
                .contains("resolveRenderableAlarmView()")
        )
        assertTrue(
            "syncChallengeUiByStage 必须经 resolveRenderableAlarmView 取视图",
            functionBody(overlaySource, "private fun syncChallengeUiByStage()")
                .contains("resolveRenderableAlarmView()")
        )
        assertTrue(
            "收殓失败必须留痕",
            overlaySource.contains("COMPLETED_STATE_ATTACH_FAILED")
        )
    }

    @Test
    fun detachedViewIsNeverSilentlyReordered() {
        val body = functionBody(overlaySource, "fun reassertAlarmOverlayOverSystemDialog(reason: String)")
        assertTrue(
            "视图脱窗时必须显式标记，而不是继续假装能重排",
            body.contains("REASSERT_VIEW_DETACHED")
        )
    }

    @Test
    fun reassertIsDebouncedAndCapped() {
        val body = functionBody(overlaySource, "fun reassertAlarmOverlayOverSystemDialog(reason: String)")

        assertTrue("去抖常量必须存在", overlaySource.contains("REASSERT_DEBOUNCE_MS"))
        assertTrue("上限常量必须存在", overlaySource.contains("REASSERT_MAX_STREAK"))
        assertTrue(body.contains("REASSERT_DEBOUNCED"))
        assertTrue(body.contains("REASSERT_STREAK_EXCEEDED"))
    }

    @Test
    fun shutdownDialogInterceptionStillUpgradesWindowMode() {
        // 防止为图省事直接删掉升级路径——那会让关机菜单重新盖住挑战界面
        assertTrue(
            "CoreService 的系统对话框拦截仍须先切窗口模式",
            coreServiceSource.contains("LockWindowManager.ensureAlarmOverlayInWindowMode()")
        )
        assertTrue(
            "拦截后仍须重新断言覆盖层层级",
            coreServiceSource.contains("LockWindowManager.reassertAlarmOverlayOverSystemDialog(")
        )
    }
}
