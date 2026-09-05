package com.zhizhi.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * TASK WP-01-T03 (v3) — DA-C structural survival/removal fixture。
 *
 * v3 SPEC §3.5 约束 C（sha256 b60da541）+ CORE_PLAN A-09 衍生 DA-C:
 * "深夜设置锁定" 已经是废弃语义(`isInSettingsLockoutPeriod` 永远返回 false),
 * 因此 UI 层残留的锁定死代码分支必须被移除;但为避免破坏调用点 / XML,
 * 兼容方法签名与 layout 占位控件必须保留。
 *
 * ─── source-text 结构断言(STATIC_CODE evidence)───────────────────────────
 *   本 fixture 不实例化任何 Android 组件,仅对生产源码/资源做纯文本结构断言,
 *   不动 app/src/main/(生产代码区域)。
 *
 * ─── 2 测试方法集 / 2 cases ──────────────────────────────────────────────
 *   #1 obsolete_branches_removed        (DA-C removal)   — 预期 RED(现仍在)
 *   #2 compatibility_method_survives    (DA-C survival)  — 预期 PASS(不变量)
 *
 * ─── RED / GREEN 语义 ────────────────────────────────────────────────────
 *   case #1 断言废弃锁定死代码分支已从 GlobalParamsActivity.kt(原 line 84-94)
 *   与 TimeConfigFragment.kt(原 line 185-199)移除。当前生产代码这些分支仍
 *   存在 → 本测试预期 FAIL(RED),等 WP-04/05 实施移除后转 GREEN。
 *
 *   case #2 断言 TimeRuleManager.isInSettingsLockoutPeriod() 兼容方法(返回
 *   false)与 layout `tv_lockout_hint` 占位控件仍存在。这两个 invariant 任何
 *   时刻都保持 → 本测试预期 PASS。
 *
 * ─── 不得接触清单 ───────────────────────────────────────────────────────
 *   - 不动 app/src/main/(生产代码区域);
 *   - 不动 Gradle / archive / long-term records。
 */
class DndRefreshStructuralFixture {

    /** 从 user.dir 向上逐级查找,兼容 gradle root / module 两种工作目录。 */
    private fun source(relativePath: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.exists() && it.length() > 0 }
            ?.readText(Charsets.UTF_8)
            ?: error("找不到 $relativePath；user.dir=${System.getProperty("user.dir")}")

    // ── DA-C 死代码字面量(废弃深夜锁定 UI 分支)──────────────────────────
    private val lockoutHintLiteral = "🔒 设置已锁定"
    private val lockoutFullLiteral =
        "🔒 设置已锁定 (深夜保护期 00:00 ~ \$refreshTime)\n当前仅供查看，无法修改配置。"

    /**
     * DA-C removal — 预期 RED。
     *
     * 废弃锁定死代码分支必须从两处 UI 生产代码移除:
     *   - GlobalParamsActivity.kt(原 line 84-94 `if (isLocked) { ... }`)
     *   - TimeConfigFragment.kt(原 line 185-199 `if (isLocked) { ... }`)
     */
    @Test
    fun obsolete_branches_removed() {
        val activity = source("app/src/main/java/com/zhizhi/app/ui/GlobalParamsActivity.kt")
        val fragment = source("app/src/main/java/com/zhizhi/app/ui/TimeConfigFragment.kt")

        assertFalse(
            "GlobalParamsActivity.kt 仍含废弃锁定完整死代码分支(应由 WP-04/05 移除)",
            activity.contains(lockoutFullLiteral)
        )
        assertFalse(
            "GlobalParamsActivity.kt 仍含废弃锁定死代码提示字面量(应由 WP-04/05 移除)",
            activity.contains(lockoutHintLiteral)
        )
        assertFalse(
            "TimeConfigFragment.kt 仍含废弃锁定死代码提示字面量(应由 WP-04/05 移除)",
            fragment.contains(lockoutHintLiteral)
        )
    }

    /**
     * DA-C survival — 预期 PASS。
     *
     * 兼容不变量:即使 UI 死代码被移除,以下必须保留以免破坏调用点 / XML:
     *   - TimeRuleManager.isInSettingsLockoutPeriod(context): Boolean 返回 false
     *   - layout 中 tv_lockout_hint 占位控件(两处主 layout)
     */
    @Test
    fun compatibility_method_survives() {
        val manager = source("app/src/main/java/com/zhizhi/app/manager/TimeRuleManager.kt")

        assertTrue(
            "TimeRuleManager 兼容方法签名 isInSettingsLockoutPeriod(context: Context): Boolean 必须保留",
            Regex(
                """fun\s+isInSettingsLockoutPeriod\s*\(\s*context\s*:\s*Context\s*\)\s*:\s*Boolean"""
            ).containsMatchIn(manager)
        )
        assertTrue(
            "isInSettingsLockoutPeriod 必须保留 return false 兼容实现",
            Regex(
                """fun\s+isInSettingsLockoutPeriod\s*\([^)]*\)\s*:\s*Boolean\s*\{\s*return\s+false\s*\}"""
            ).containsMatchIn(manager)
        )

        val activityLayout = source("app/src/main/res/layout/activity_global_params_new.xml")
        val dialogLayout = source("app/src/main/res/layout/fragment_time_config.xml")

        assertTrue(
            "activity_global_params_new.xml 必须保留 tv_lockout_hint 占位控件",
            activityLayout.contains("@+id/tv_lockout_hint")
        )
        assertTrue(
            "fragment_time_config.xml 必须保留 tv_lockout_hint 占位控件",
            dialogLayout.contains("@+id/tv_lockout_hint")
        )
    }
}
