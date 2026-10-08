package com.decikeeper.app.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每日基准必须在刷新时刻写入保护开关状态（防卸载、小窗增强、开发者选项拦截等）。
 *
 * 保护开关的基准只由 `TimeRuleManager.saveBaselineParams` 写入。若每日快照漏掉它，
 * 基准就只会在用户当天第一次保存规则时才被懒写，把当天手动开启的开关也记成基准，
 * 之后「恢复基准立即关闭」失效，被误判为需要次日生效。
 */
class MonitorServiceDailyBaselineContractTest {

    @Test
    fun dailyBaselineSnapshotAlsoRecordsProtectionSwitchStates() {
        val source = source("app/src/main/java/com/decikeeper/app/service/MonitorService.kt")
        val start = source.indexOf("Daily Baseline Snapshot Updated")
        assertTrue("daily baseline block not found", start > 0)
        val block = source.substring(source.lastIndexOf("if (lastBaselineBizDate", start), start)

        assertTrue(block.contains("TimeRuleManager.saveBaselineSnapshot(context)"))
        assertTrue(
            "每日基准快照必须同时调用 saveBaselineParams，否则保护开关基准会被懒写污染",
            block.contains("TimeRuleManager.saveBaselineParams(context)")
        )
    }

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, path),
            File(cwd, path.removePrefix("app/")),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, path.removePrefix("app/"))
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $path from ${cwd.absolutePath}")
    }
}
