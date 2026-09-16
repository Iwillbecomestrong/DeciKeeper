/**
 * =================================================================================
 * [Context Header]
 * * @file: MainActivityFallbackPendingExtrasTest.kt
 * * @role: Test
 * * @description: 锁死 MainActivity.onCreate 在「未同意」兜底路径会先保存当前 intent extras。
 * * @responsibilities:
 * 1. 防止 REQ-5 Round 4 Fix #2 引入的"未同意 + incoming intent has extras → 先 PendingMainActivityExtras.save"
 *    被后续重构悄悄移除,从而让 MonitorService 等 PendingIntent 的 show_permission_dialog
 *    extras 在兜底分支继续丢。
 * 2. 源码 grep 锁契约(纯 JVM;在 Android runtime 上的真实流转由 emulator-tester 验证)。
 * * @constraints (必读):
 * 1. 仅检查源文件结构 — 不依赖 Android Context。
 * 2. 真机的 pending-extras 持久化由 EMULATOR_TEST_REPORT MAJOR-4 验证(BLOCKED)。
 * =================================================================================
 */
package com.decikeeper.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test


class MainActivityFallbackPendingExtrasTest {

    private fun readMainActivitySource(): String {
        val candidates = listOf(
            java.io.File("src/main/java/com/decikeeper/app/ui/MainActivity.kt"),
            java.io.File("app/src/main/java/com/decikeeper/app/ui/MainActivity.kt")
        )
        for (f in candidates) {
            if (f.exists()) return f.readText(Charsets.UTF_8)
        }
        throw IllegalStateException(
            "MainActivity.kt not found in any of: " +
                candidates.joinToString { it.absolutePath }
        )
    }

    @Test
    fun mainActivityOnCreateCallssPendingMainActivityExtrasSaveInFallback() {
        // REQ-5 Round 4 Fix #2 防回归:MainActivity.onCreate 未同意分支必须调
        // PendingMainActivityExtras.save(this, incoming) 一次,再 startActivity
        // PrivacyConsentActivity。否则 PendingIntent 路径来的 extras 会再被吞。
        val src = readMainActivitySource()
        assertTrue(
            "MainActivity.kt 必须显式调用 PendingMainActivityExtras.save(this, incoming) " +
                "保存兜底分支的 extras",
            src.contains("PendingMainActivityExtras.save(this, incoming)")
        )
    }

    @Test
    fun mainActivityOnCreateChecksExtrasIsEmptyBeforeSaving() {
        // 兜底路径只在 incoming intent 真有 extras 时才值得存;
        // 必须出现 .isEmpty / != null 这一类判断。
        val src = readMainActivitySource()
        // 接受两种等价写法:(a) 直接 incoming.extras?.isEmpty 检查,
        // (b) 提取到本地 val incomingExtras 后再判断。
        val hasExtrasGuard =
            // 写法 (a): 直接链式调用
            (src.contains("incoming.extras") &&
                (src.contains("!incoming.extras.isEmpty") ||
                    src.contains("!incoming.extras!!.isEmpty") ||
                    src.contains("incoming.extras?.isEmpty == false") ||
                    src.contains("incoming.extras != null"))) ||
                // 写法 (b): 提取本地 val 再判断 (本轮实际写法)
                (src.contains("val incomingExtras") &&
                    (src.contains("!incomingExtras.isEmpty") ||
                        src.contains("incomingExtras != null")))
        assertTrue(
            "MainActivity.kt 兜底分支必须先检查 extras 非空再 save," +
                "避免空 Bundle 也写 prefs",
            hasExtrasGuard
        )
    }

    @Test
    fun mainActivityOnCreateLogsBlockerDeviceTodo() {
        // MAJOR-4/5:在真机不可用的环境,代码里必须埋 TODO 日志 +
        // BLOCKED_NEEDS_REAL_DEVICE 字样,便于交接给 emulator-tester 时一眼定位。
        val src = readMainActivitySource()
        assertEquals(
            true,
            src.contains("TODO(round-4 MAJOR-4)") && src.contains("BLOCKED_NEEDS_REAL_DEVICE")
        )
    }
}
