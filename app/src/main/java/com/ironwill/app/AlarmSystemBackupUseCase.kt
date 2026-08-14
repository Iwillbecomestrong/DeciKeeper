package com.ironwill.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.util.Log

/**
 * [REQ-4] 同步系统闹钟任务栈策略 use case。
 *
 * 根因（见 PLAN §2 RC-001/RC-002）：
 * - 直接发 `ACTION_SET_ALARM` 进入 OEM 时钟 App 的"编辑闹钟"页时，如果 OEM 时钟 App
 *   没有自己的根任务在系统任务栈里，用户保存闹钟后编辑页结束，Android 会回退到
 *   调用方（IronWill）的任务，造成"保存完自动回到 IronWill"。
 * - 旧实现仅在 `pickClockCandidate()` 选包，没有建立该包的根任务。
 *
 * 修复策略（warm/cold 区分）：
 * - **Cold（后台无该包进程）** → `TWO_STEP_WITH_LAUNCHER`：先 start launcher intent
 *   把该包的根任务建立起来，再 start `ACTION_SET_ALARM` 进去。保存闹钟后，
 *   编辑页结束，Android 会回退到该包的根任务（也就是 launcher 页），而不会回
 *   到 IronWill。
 * - **Warm（后台已有该包进程）** → `SINGLE_STEP_WARM`：直接发 `ACTION_SET_ALARM`，
 *   系统会复用已有任务栈，避免重复启动 launcher 造成闪动或重复打开。
 * - **No launcher（OEM 包不暴露 launcher intent）** → `SINGLE_STEP_NO_LAUNCHER`：
 *   单步降级，仅发 `ACTION_SET_ALARM`，并打 `[ALARM_SYNC] fallback-single-step`
 *   日志，便于 OEM 真机排查。
 *
 * 不修改 AlarmScheduler/AlarmReceiver/AlarmVibrationService/AlarmGuardianService
 * 主调度链路（见 README §3 红线 4）。
 */
class AlarmSystemBackupUseCase(
    private val clockPackageResolver: ClockPackageResolver,
    private val activityStarter: ActivityStarter = ContextActivityStarter
) {

    /** 真实 Android 包管理器适配器，把 PackageManager 方法桥接到 [ClockPackageResolver]。 */
    constructor(packageManager: PackageManager) : this(PackageManagerClockPackageResolver(packageManager))

    companion object {
        private const val TAG = "AlarmSystemBackupUseCase"

        /**
         * 纯函数：根据"目标时钟包是否有 launcher intent" + "后台是否已有进程"
         * 决定启动计划。独立出来方便单测；签名稳定（reflection lock）。
         */
        @JvmStatic
        fun decideLaunchPlan(hasLauncher: Boolean, isWarm: Boolean): BackupLaunchPlan.Kind {
            if (!hasLauncher) return BackupLaunchPlan.Kind.SINGLE_STEP_NO_LAUNCHER
            return if (isWarm) BackupLaunchPlan.Kind.SINGLE_STEP_WARM
            else BackupLaunchPlan.Kind.TWO_STEP_WITH_LAUNCHER
        }

        /**
         * 纯逻辑版 [buildLaunchPlan]，不写日志，方便 JVM 单测覆盖。
         * 调用方通常用 [buildLaunchPlan]；测试用本方法避免依赖 android.util.Log mock。
         */
        @JvmStatic
        fun computePlan(
            targetIntent: Intent,
            launcherIntent: Intent?,
            kind: BackupLaunchPlan.Kind
        ): BackupLaunchPlan {
            return BackupLaunchPlan(
                kind = kind,
                launcherIntent = launcherIntent,
                setAlarmIntent = targetIntent
            )
        }
    }

    /**
     * 解析 ACTION_SET_ALARM 的候选处理器列表。
     */
    fun querySetAlarmCandidates(baseIntent: Intent): List<ResolveInfo> {
        return clockPackageResolver.queryIntentActivities(baseIntent, 0)
    }

    /**
     * 按"包名包含 clock/deskclock"优先选包，否则回退到第一个候选。
     * 排序稳定：相同关键字的候选保持 queryIntentActivities 的原始顺序。
     */
    fun pickClockCandidate(candidates: List<ResolveInfo>): ResolveInfo? {
        if (candidates.isEmpty()) return null
        val preferred = candidates.firstOrNull {
            val pkg = it.activityInfo.packageName.lowercase()
            pkg.contains("clock") || pkg.contains("deskclock")
        }
        return preferred ?: candidates.first()
    }

    /**
     * 获取目标时钟包的 launcher Intent。
     * 极少数 OEM 包不暴露 launcher intent 时返回 null。
     */
    fun resolveLauncherIntent(targetPackage: String): Intent? {
        return clockPackageResolver.getLaunchIntentForPackage(targetPackage)
    }

    /**
     * 判断目标包是否已在后台运行（warm 状态）。
     *
     * 实现：用 [ActivityManager.RunningAppProcesses] 扫描进程列表。
     * API 30+ 上 `getRunningAppProcesses` 仍返回自己应用的信息但对其他应用受限，
     * 我们这里用作 best-effort；检测失败统一 fallback false（保守走 cold 两段式）。
     *
     * [REQ-4 round 2] TODO: 不同 OEM 上 `ActivityManager` API 行为差异较大，需
     * 在 Vivo / iQOO 真机上验证准确性。临时无法真机验证，先打日志留痕。
     */
    fun isClockAppInBackground(context: Context, targetPackage: String): Boolean {
        // [REQ-4 round 2 TODO] 真机验证占位日志 — ActivityManager 在不同 OEM 上行为
        // 不一致；当前环境无法连接 Vivo/iQOO 真机，留 TODO 待 REAL_DEVICE_PLAN.md
        // 落实后由 tester 验证或由 developer 切到更可靠的 UsageStatsManager。
        Log.w(
            TAG,
            "[ALARM_SYNC] TODO(round-2): needs real-device verification for OEM clock " +
                "app background detection (Vivo/iQOO); see REAL_DEVICE_PLAN.md " +
                "target=$targetPackage"
        )
        if (targetPackage.isBlank()) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return try {
            @Suppress("DEPRECATION")
            val procs = am.runningAppProcesses ?: return false
            procs.any { it.processName == targetPackage || it.processName.startsWith("$targetPackage:") }
        } catch (e: Exception) {
            // 部分 OEM 在 API 30+ 对 getRunningAppProcesses 抛 SecurityException；
            // 失败保守 fallback false，走 cold 两段式。
            Log.w(TAG, "[ALARM_SYNC] isClockAppInBackground probe failed, fallback cold", e)
            false
        }
    }

    /**
     * 主流程：构建一次完整的同步系统闹钟启动计划。
     */
    fun buildLaunchPlan(
        context: Context,
        targetPackage: String,
        targetIntent: Intent
    ): BackupLaunchPlan {
        val launcherIntent = resolveLauncherIntent(targetPackage)
        val isWarm = if (launcherIntent != null) isClockAppInBackground(context, targetPackage) else false
        val kind = decideLaunchPlan(hasLauncher = launcherIntent != null, isWarm = isWarm)
        Log.i(
            TAG,
            "[ALARM_SYNC] buildLaunchPlan target=$targetPackage " +
                "hasLauncher=${launcherIntent != null} warm=$isWarm kind=$kind"
        )
        return computePlan(targetIntent, launcherIntent, kind)
    }

    /**
     * 执行 plan：
     * - `TWO_STEP_WITH_LAUNCHER` (cold)：先 start launcher intent 建根任务，再 start `ACTION_SET_ALARM`。
     * - `SINGLE_STEP_WARM` (warm)：直接 start `ACTION_SET_ALARM`，系统复用后台任务栈。
     * - `SINGLE_STEP_NO_LAUNCHER` (no launcher)：单步降级，仅 start `ACTION_SET_ALARM`。
     *
     * launcher 启动失败会被吞掉并降级（仍尝试 `ACTION_SET_ALARM`）；
     * `ACTION_SET_ALARM` 失败向上抛由调用方 Toast。
     */
    fun execute(context: Context, plan: BackupLaunchPlan) {
        // [REQ-4 round 2 TODO] 真机验证占位日志 — execute() 是 cold/warm/no-launcher
        // 三条路径的分支点，需要在真机上验证 warm 检测准确时确实走 SINGLE_STEP_WARM，
        // 否则会出现闪动或重复打开。当前环境无法连接 Vivo/iQOO 真机，留 TODO。
        Log.w(
            TAG,
            "[ALARM_SYNC] TODO(round-2): execute() needs real-device verification " +
                "for OEM clock app behavior (Vivo/iQOO); see REAL_DEVICE_PLAN.md " +
                "kind=${plan.kind}"
        )
        when (plan.kind) {
            BackupLaunchPlan.Kind.TWO_STEP_WITH_LAUNCHER -> {
                val launcher = plan.launcherIntent
                    ?: error("TWO_STEP plan must carry a launcher intent")
                // [REQ-4] 用 NewTask 启动 launcher，先在系统任务栈里建立时钟包
                // 自己的根任务。不加 FLAG_ACTIVITY_CLEAR_TASK，否则会清掉时钟 App
                // 可能已存在的任务栈，破坏"热启动留原页"的体验。
                val launcherWithFlags = Intent(launcher).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    activityStarter.startActivity(context, launcherWithFlags)
                    Log.i(TAG, "[ALARM_SYNC] launcher started target=${launcher.component}")
                } catch (e: Exception) {
                    Log.w(TAG, "[ALARM_SYNC] launcher start failed, fallback single-step", e)
                }
                try {
                    activityStarter.startActivity(context, plan.setAlarmIntent)
                    Log.i(TAG, "[ALARM_SYNC] ACTION_SET_ALARM started after launcher")
                } catch (e: Exception) {
                    Log.e(TAG, "[ALARM_SYNC] ACTION_SET_ALARM start failed", e)
                    throw e
                }
            }
            BackupLaunchPlan.Kind.SINGLE_STEP_WARM -> {
                // 热启动：后台已有该包进程，直接发 ACTION_SET_ALARM 让系统复用
                // 已存在任务栈，避免两段式造成的闪动 / 重复打开。
                Log.i(TAG, "[ALARM_SYNC] warm single-step, reusing existing task")
                try {
                    activityStarter.startActivity(context, plan.setAlarmIntent)
                    Log.i(TAG, "[ALARM_SYNC] ACTION_SET_ALARM started (warm)")
                } catch (e: Exception) {
                    Log.e(TAG, "[ALARM_SYNC] ACTION_SET_ALARM start failed (warm)", e)
                    throw e
                }
            }
            BackupLaunchPlan.Kind.SINGLE_STEP_NO_LAUNCHER -> {
                val pkg = plan.setAlarmIntent.`package`
                Log.w(TAG, "[ALARM_SYNC] fallback-single-step, no launcher for $pkg")
                try {
                    activityStarter.startActivity(context, plan.setAlarmIntent)
                    Log.i(TAG, "[ALARM_SYNC] ACTION_SET_ALARM started (no-launcher fallback)")
                } catch (e: Exception) {
                    Log.e(TAG, "[ALARM_SYNC] ACTION_SET_ALARM start failed (no-launcher fallback)", e)
                    throw e
                }
            }
        }
    }
}

/**
 * 启动计划值对象。kind 仅供日志与单测，调用方按 kind 分支。
 *
 * - [Kind.TWO_STEP_WITH_LAUNCHER]：cold 场景，先 start launcher intent 建根任务，
 *   再 start `ACTION_SET_ALARM`。优先选择该方案，确保保存闹钟后回到该包的
 *   launcher 页，而不是回退到 IronWill 任务。
 * - [Kind.SINGLE_STEP_WARM]：warm 场景，后台已有该包进程，直接 start
 *   `ACTION_SET_ALARM`，避免两段式造成的闪动 / 重复打开。
 * - [Kind.SINGLE_STEP_NO_LAUNCHER]：目标包没有 launcher intent 时降级为单步
 *   `ACTION_SET_ALARM`；保留旧行为以避免对不支持的 OEM 包做无效启动。
 */
data class BackupLaunchPlan(
    val kind: Kind,
    val launcherIntent: Intent?,
    val setAlarmIntent: Intent
) {
    enum class Kind {
        TWO_STEP_WITH_LAUNCHER,
        SINGLE_STEP_WARM,
        SINGLE_STEP_NO_LAUNCHER
    }
}

/**
 * 最小化的 PackageManager 抽象，方便 JVM 单测注入 fake。
 * 仅暴露 use case 真实调用的两个方法。
 */
interface ClockPackageResolver {
    fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfo>
    fun getLaunchIntentForPackage(packageName: String): Intent?
}

/** 生产环境实现：直接桥接 Android 的 PackageManager。 */
class PackageManagerClockPackageResolver(private val packageManager: PackageManager) : ClockPackageResolver {
    override fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfo> {
        return packageManager.queryIntentActivities(intent, flags)
    }
    override fun getLaunchIntentForPackage(packageName: String): Intent? {
        return packageManager.getLaunchIntentForPackage(packageName)
    }
}

/**
 * 最小化的 Activity 启动抽象，方便 JVM 单测注入 fake。
 * 仅暴露 use case 真实调用的 [Context.startActivity]。
 */
interface ActivityStarter {
    fun startActivity(context: Context, intent: Intent)
}

/** 生产环境实现：直接调用 [Context.startActivity]。 */
object ContextActivityStarter : ActivityStarter {
    override fun startActivity(context: Context, intent: Intent) {
        context.startActivity(intent)
    }
}
