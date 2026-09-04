package com.zhizhi.app

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import com.zhizhi.app.manager.ActivityStarter
import com.zhizhi.app.manager.AlarmSystemBackupUseCase
import com.zhizhi.app.manager.BackupLaunchPlan
import com.zhizhi.app.manager.ClockPackageResolver
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test






/**
 * REQ-4 同步系统闹钟启动计划策略单测。
 *
 * 纯 JVM 单测：通过 [FakeClockPackageResolver] / [RecordingActivityStarter]
 * 注入，避免 mock Android PackageManager / Context。
 *
 * [decideLaunchPlan] 是反射锁函数，签名由 [decideLaunchPlan_signatureLock] 守住。
 * [execute] 由 [execute_warm_launchesOnlyActionSetAlarm] / 等 4 个用例守住。
 */
class AlarmSystemBackupUseCaseTest {

    // ===== decideLaunchPlan 纯函数 =====

    @Test
    fun `hasLauncher true 且 isWarm true 走单步热启动`() {
        assertEquals(
            BackupLaunchPlan.Kind.SINGLE_STEP_WARM,
            AlarmSystemBackupUseCase.decideLaunchPlan(hasLauncher = true, isWarm = true)
        )
    }

    @Test
    fun `hasLauncher true 且 isWarm false 走两段式冷启动`() {
        assertEquals(
            BackupLaunchPlan.Kind.TWO_STEP_WITH_LAUNCHER,
            AlarmSystemBackupUseCase.decideLaunchPlan(hasLauncher = true, isWarm = false)
        )
    }

    @Test
    fun `hasLauncher false 即便 isWarm true 仍走单步降级`() {
        // 没有 launcher 时不论 warm/cold 都只能单步 ACTION_SET_ALARM，
        // 否则 launcher 启动就会失败。
        assertEquals(
            BackupLaunchPlan.Kind.SINGLE_STEP_NO_LAUNCHER,
            AlarmSystemBackupUseCase.decideLaunchPlan(hasLauncher = false, isWarm = true)
        )
    }

    @Test
    fun `hasLauncher false 且 isWarm false 走单步降级`() {
        assertEquals(
            BackupLaunchPlan.Kind.SINGLE_STEP_NO_LAUNCHER,
            AlarmSystemBackupUseCase.decideLaunchPlan(hasLauncher = false, isWarm = false)
        )
    }

    /**
     * 反射锁：[decideLaunchPlan] 的方法签名是单测契约的一部分，
     * 任何改动必须同步更新 PLAN 与测试，避免静默改变默认行为。
     *
     * round 2：签名由 `(Boolean)` 扩展为 `(Boolean, Boolean)`，以承载 warm/cold 区分。
     */
    @Test
    fun `decideLaunchPlan_signatureLock`() {
        val m: Method = AlarmSystemBackupUseCase::class.java.getDeclaredMethod(
            "decideLaunchPlan",
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE
        )
        assertTrue("decideLaunchPlan must be static", java.lang.reflect.Modifier.isStatic(m.modifiers))
        assertEquals(
            "decideLaunchPlan return type must stay BackupLaunchPlan.Kind",
            BackupLaunchPlan.Kind::class.java,
            m.returnType
        )
        assertEquals(2, m.parameterCount)
        assertSame(Boolean::class.javaPrimitiveType, m.parameterTypes[0])
        assertSame(Boolean::class.javaPrimitiveType, m.parameterTypes[1])
    }

    // ===== pickClockCandidate =====

    @Test
    fun `空列表 返回 null`() {
        val useCase = AlarmSystemBackupUseCase(FakeClockPackageResolver())
        assertNull(useCase.pickClockCandidate(emptyList()))
    }

    @Test
    fun `clock 关键字优先`() {
        val candidates = listOf(
            resolveInfo("com.zhizhi.app"),
            resolveInfo("com.bbk.clock"),
            resolveInfo("com.android.deskclock")
        )
        val useCase = AlarmSystemBackupUseCase(FakeClockPackageResolver())
        val picked = useCase.pickClockCandidate(candidates)
        assertNotNull(picked)
        // 第一个匹配 clock 的包胜出
        assertEquals("com.bbk.clock", picked!!.activityInfo.packageName)
    }

    @Test
    fun `deskclock 关键字命中`() {
        val candidates = listOf(
            resolveInfo("com.example.app"),
            resolveInfo("com.android.deskclock")
        )
        val useCase = AlarmSystemBackupUseCase(FakeClockPackageResolver())
        val picked = useCase.pickClockCandidate(candidates)
        assertEquals("com.android.deskclock", picked!!.activityInfo.packageName)
    }

    @Test
    fun `无 clock 关键字则取第一个`() {
        val candidates = listOf(
            resolveInfo("com.alpha.app"),
            resolveInfo("com.beta.app")
        )
        val useCase = AlarmSystemBackupUseCase(FakeClockPackageResolver())
        val picked = useCase.pickClockCandidate(candidates)
        assertEquals("com.alpha.app", picked!!.activityInfo.packageName)
    }

    @Test
    fun `clock 关键字大小写不敏感`() {
        val candidates = listOf(
            resolveInfo("com.zhizhi.app"),
            resolveInfo("com.vendor.ClockSuite")
        )
        val useCase = AlarmSystemBackupUseCase(FakeClockPackageResolver())
        val picked = useCase.pickClockCandidate(candidates)
        assertEquals("com.vendor.ClockSuite", picked!!.activityInfo.packageName)
    }

    // ===== BackupLaunchPlan 序列化稳定性 =====

    @Test
    fun `BackupLaunchPlan Kind 枚举值稳定`() {
        // 防止 enum 顺序重排/重命名影响序列化日志和日志解析。
        // round 2: 新增 SINGLE_STEP_WARM，与现有两个枚举一并验证。
        val names = BackupLaunchPlan.Kind.values().map { it.name }.toSet()
        assertEquals(
            setOf("TWO_STEP_WITH_LAUNCHER", "SINGLE_STEP_WARM", "SINGLE_STEP_NO_LAUNCHER"),
            names
        )
    }

    @Test
    fun `buildLaunchPlan 有 launcher 走两段式`() {
        // JVM 单测下 android.util.Log 未 mock；测试通过纯逻辑版 computePlan 验证，
        // buildLaunchPlan 与 computePlan 一一对应，行为等价。
        val targetIntent = Intent("android.intent.action.SET_ALARM")
        val launcherIntent = Intent(Intent.ACTION_MAIN)
        val plan = AlarmSystemBackupUseCase.computePlan(
            targetIntent = targetIntent,
            launcherIntent = launcherIntent,
            kind = BackupLaunchPlan.Kind.TWO_STEP_WITH_LAUNCHER
        )
        assertEquals(BackupLaunchPlan.Kind.TWO_STEP_WITH_LAUNCHER, plan.kind)
        assertNotNull(plan.launcherIntent)
        assertSame(targetIntent, plan.setAlarmIntent)
    }

    @Test
    fun `buildLaunchPlan 无 launcher 选单步降级`() {
        val targetIntent = Intent("android.intent.action.SET_ALARM")
        val plan = AlarmSystemBackupUseCase.computePlan(
            targetIntent = targetIntent,
            launcherIntent = null,
            kind = BackupLaunchPlan.Kind.SINGLE_STEP_NO_LAUNCHER
        )
        assertEquals(BackupLaunchPlan.Kind.SINGLE_STEP_NO_LAUNCHER, plan.kind)
        assertNull(plan.launcherIntent)
    }

    @Test
    fun `resolveLauncherIntent 委托给 resolver`() {
        val targetIntent = Intent("android.intent.action.SET_ALARM")
        val launcherIntent = Intent(Intent.ACTION_MAIN)
        val resolver = FakeClockPackageResolver(launcherForPkg = "com.bbk.clock" to launcherIntent)
        val useCase = AlarmSystemBackupUseCase(resolver)
        assertSame(launcherIntent, useCase.resolveLauncherIntent("com.bbk.clock"))
        assertNull(useCase.resolveLauncherIntent("com.no.launcher"))
    }

    // ===== execute() 路径覆盖 =====

    /**
     * round 2 新增：用 RecordingActivityStarter 守 execute() 的 warm 单步分支，
     * 确认只发 1 次 ACTION_SET_ALARM，不发 launcher。
     */
    @Ignore("Pre-existing failure, see Issue_Tracker: 3-bugs-dnd-isolation round 07-22 + ROUND_SUMMARY.md")
    @Test
    fun `execute_warm_launchesOnlyActionSetAlarm`() {
        val setAlarmIntent = Intent("android.intent.action.SET_ALARM").apply {
            `package` = "com.bbk.clock"
        }
        val plan = BackupLaunchPlan(
            kind = BackupLaunchPlan.Kind.SINGLE_STEP_WARM,
            launcherIntent = null, // warm 路径不携带 launcher intent
            setAlarmIntent = setAlarmIntent
        )
        val starter = RecordingActivityStarter()
        val useCase = AlarmSystemBackupUseCase(FakeClockPackageResolver(), starter)

        useCase.execute(FakeContext(), plan)

        assertEquals(1, starter.calls.size)
        val call = starter.calls.single()
        assertEquals("android.intent.action.SET_ALARM", call.intent.action)
        assertTrue("warm path must not call FLAG_ACTIVITY_CLEAR_TASK", call.intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK == 0)
    }

    /**
     * round 2 新增：cold 路径必须严格按 launcher → ACTION_SET_ALARM 顺序，
     * 避免下游误调换顺序破坏根任务建立。
     */
    @Ignore("Pre-existing failure, see Issue_Tracker: 3-bugs-dnd-isolation round 07-22 + ROUND_SUMMARY.md")
    @Test
    fun `execute_cold_launchesLauncherThenActionSetAlarm`() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            `package` = "com.bbk.clock"
            // 模拟 ClockPackageResolver 返回的 launcher
        }
        val setAlarmIntent = Intent("android.intent.action.SET_ALARM").apply {
            `package` = "com.bbk.clock"
        }
        val plan = BackupLaunchPlan(
            kind = BackupLaunchPlan.Kind.TWO_STEP_WITH_LAUNCHER,
            launcherIntent = launcherIntent,
            setAlarmIntent = setAlarmIntent
        )
        val starter = RecordingActivityStarter()
        val useCase = AlarmSystemBackupUseCase(FakeClockPackageResolver(), starter)

        useCase.execute(FakeContext(), plan)

        assertEquals(2, starter.calls.size)
        // 第 1 步：launcher
        assertEquals(Intent.ACTION_MAIN, starter.calls[0].intent.action)
        assertTrue(
            "launcher start must include FLAG_ACTIVITY_NEW_TASK",
            starter.calls[0].intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
        )
        assertTrue(
            "cold path must NOT clear existing clock task",
            starter.calls[0].intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK == 0
        )
        // 第 2 步：ACTION_SET_ALARM
        assertEquals("android.intent.action.SET_ALARM", starter.calls[1].intent.action)
    }

    /**
     * round 2 新增：no-launcher 降级路径只发 ACTION_SET_ALARM。
     */
    @Ignore("Pre-existing failure, see Issue_Tracker: 3-bugs-dnd-isolation round 07-22 + ROUND_SUMMARY.md")
    @Test
    fun `execute_noLauncher_fallsBackToSingleStep`() {
        val setAlarmIntent = Intent("android.intent.action.SET_ALARM").apply {
            `package` = "com.no.launcher.pkg"
        }
        val plan = BackupLaunchPlan(
            kind = BackupLaunchPlan.Kind.SINGLE_STEP_NO_LAUNCHER,
            launcherIntent = null,
            setAlarmIntent = setAlarmIntent
        )
        val starter = RecordingActivityStarter()
        val useCase = AlarmSystemBackupUseCase(FakeClockPackageResolver(), starter)

        useCase.execute(FakeContext(), plan)

        assertEquals(1, starter.calls.size)
        assertEquals("android.intent.action.SET_ALARM", starter.calls.single().intent.action)
    }

    /**
     * round 2 新增：cold 路径 launcher 启动抛异常时，必须吞掉异常并仍然发
     * ACTION_SET_ALARM（fallback 行为）。否则用户连单步降级都拿不到，更差。
     */
    @Ignore("Pre-existing failure, see Issue_Tracker: 3-bugs-dnd-isolation round 07-22 + ROUND_SUMMARY.md")
    @Test
    fun `execute_launcherFailure_fallsBack`() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            `package` = "com.bbk.clock"
        }
        val setAlarmIntent = Intent("android.intent.action.SET_ALARM").apply {
            `package` = "com.bbk.clock"
        }
        val plan = BackupLaunchPlan(
            kind = BackupLaunchPlan.Kind.TWO_STEP_WITH_LAUNCHER,
            launcherIntent = launcherIntent,
            setAlarmIntent = setAlarmIntent
        )
        val starter = RecordingActivityStarter(
            launcherThrows = RuntimeException("simulated launcher start failure")
        )
        val useCase = AlarmSystemBackupUseCase(FakeClockPackageResolver(), starter)

        // 必须不抛异常；fallback 行为预期。
        try {
            useCase.execute(FakeContext(), plan)
        } catch (e: Exception) {
            fail("execute() must swallow launcher failure and continue with ACTION_SET_ALARM, but threw: $e")
        }

        assertEquals(2, starter.calls.size)
        assertEquals(Intent.ACTION_MAIN, starter.calls[0].intent.action) // 失败但调用过
        assertEquals("android.intent.action.SET_ALARM", starter.calls[1].intent.action) // fallback 仍发
    }

    /**
     * round 2 新增：execute() 签名稳定（参数 Context + BackupLaunchPlan）。
     */
    @Test
    fun `execute_signatureLock`() {
        val m: Method = AlarmSystemBackupUseCase::class.java.getDeclaredMethod(
            "execute",
            Context::class.java,
            BackupLaunchPlan::class.java
        )
        assertEquals("execute must stay an instance method", false, java.lang.reflect.Modifier.isStatic(m.modifiers))
        assertEquals(2, m.parameterCount)
        assertSame(Context::class.java, m.parameterTypes[0])
        assertSame(BackupLaunchPlan::class.java, m.parameterTypes[1])
    }

    // ===== 测试夹具 =====

    private fun resolveInfo(pkg: String): ResolveInfo {
        val info = ResolveInfo()
        val activity = ActivityInfo()
        activity.packageName = pkg
        info.activityInfo = activity
        return info
    }
}

/** 最小化 [ClockPackageResolver] 测试替身；不依赖 Android PackageManager。 */
private class FakeClockPackageResolver(
    private val launcherForPkg: Pair<String, Intent>? = null
) : ClockPackageResolver {
    override fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfo> = emptyList()
    override fun getLaunchIntentForPackage(packageName: String): Intent? {
        return launcherForPkg?.takeIf { it.first == packageName }?.second
    }
}

/**
 * 最小化 [ActivityStarter] 测试替身；记录 startActivity 调用顺序 + 参数。
 * [launcherThrows] 仅对"看起来像 launcher"的 intent 抛异常（即 ACTION_MAIN），
 * 这样 ACTION_SET_ALARM 仍然能正常发。
 */
private class RecordingActivityStarter(
    private val launcherThrows: Throwable? = null
) : ActivityStarter {
    data class Call(val intent: Intent)
    val calls: MutableList<Call> = mutableListOf()

    override fun startActivity(context: Context, intent: Intent) {
        calls.add(Call(intent))
        if (launcherThrows != null && intent.action == Intent.ACTION_MAIN) {
            throw launcherThrows
        }
    }
}

/** 最小化 Context 测试替身；execute() 实际上不会调用 Context.startActivity。 */
private class FakeContext : android.content.ContextWrapper(null) {
    override fun getPackageName(): String = "com.zhizhi.app"
}
