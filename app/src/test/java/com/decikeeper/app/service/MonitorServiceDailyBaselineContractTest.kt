package com.decikeeper.app.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每日基准必须包含保护开关状态，并且在所有能改动开关的入口之前拍好。
 *
 * 保护开关的基准只由 `TimeRuleManager.saveBaselineParams` 写入。若每日快照漏掉它，
 * 基准就只会在用户当天某次操作时才被懒写，把当天手动开启的开关也记成基准，
 * 之后「恢复基准立即关闭」失效，被误判为需要次日生效。
 */
class MonitorServiceDailyBaselineContractTest {

    @Test
    fun dailySnapshotAppliesDuePendingThenRecordsProtectionSwitchStates() {
        val source = source("app/src/main/java/com/decikeeper/app/manager/DailyBaselineManager.kt")

        assertTrue(source.contains("TimeRuleManager.saveBaselineSnapshot(context)"))
        assertTrue(
            "每日基准快照必须同时调用 saveBaselineParams，否则保护开关基准会被懒写污染",
            source.contains("TimeRuleManager.saveBaselineParams(context)")
        )
        assertTrue(source.contains("TimeRuleManager.applyPendingToActive(context)"))
        assertTrue(
            "必须先并入到期的待修改记录再拍照",
            source.indexOf("applyDuePendingChanges(context)") < source.indexOf("TimeRuleManager.saveBaselineParams(context)")
        )
    }

    @Test
    fun snapshotRunsFromEveryEntryPointBeforeUserCanToggleSwitches() {
        listOf(
            "app/src/main/java/com/decikeeper/app/service/MonitorService.kt",
            "app/src/main/java/com/decikeeper/app/MyApplication.kt",
            "app/src/main/java/com/decikeeper/app/ui/MainActivity.kt"
        ).forEach {
            assertTrue("$it 必须调用 DailyBaselineManager.ensureTodaySnapshot", source(it).contains("DailyBaselineManager.ensureTodaySnapshot("))
        }
    }

    @Test
    fun savingRulesNoLongerTriggersALazyBaselineSnapshot() {
        assertTrue(!source("app/src/main/java/com/decikeeper/app/manager/TimeRuleManager.kt").contains("ensureFreshBaseline"))
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
