/**
 * =================================================================================
 * [Context Header]
 * * @file: DailyBaselineManager.kt
 * * @role: Domain Skill
 * * @description: 每日基准快照的幂等入口。
 * * @responsibilities:
 * 1. 在新业务日的刷新时刻之后，只拍一次基准（规则、黑名单、应用窗口、保护开关状态）。
 * 2. 供监控服务、Application、主界面共用，谁先到谁拍，后到的直接返回。
 * * @context:
 * - 上游: MonitorService, MyApplication, MainActivity
 * - 下游: TimeRuleManager, CreditManager, AppWindowManager
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 必须幂等且开销极小：同一业务日第二次调用只读两个偏好值。
 * 3. 基准只在「每日刷新时刻之后第一次到这里」与「适应期结束」两个时刻写入，严禁在保存规则时补拍。
 * 4. Direct Boot 未解锁时不得调用（会访问 CE 存储）。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.Context
import android.util.Log

object DailyBaselineManager {

    private const val TAG = "DailyBaselineManager"
    private const val KEY_LAST_BASELINE_TIME = "last_baseline_update_time"

    /**
     * 新业务日且已过刷新时刻时写入当天基准。
     *
     * 入口放在所有能改动保护开关的地方之前（Application、主界面、监控服务），
     * 这样即使刷新时刻那一刻服务没在运行（关机、被杀），用户也是先触发这里、再去动开关，
     * 当天基准不会混入当天的手动改动。
     *
     * @return 本次是否写入了基准
     */
    @Synchronized
    fun ensureTodaySnapshot(context: Context): Boolean {
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val lastBizDate = CreditManager.getBusinessDateStringAt(context, prefs.getLong(KEY_LAST_BASELINE_TIME, 0L))
        val currentBizDate = CreditManager.getBusinessDateString(context)
        if (lastBizDate == currentBizDate || !TimeRuleManager.isAfterRefreshTime(context)) return false

        // 先把已到期的待修改记录并入当前生效值，再拍照：基准 = 前一逻辑日最终生效值 + 在刷新时刻到期的待修改。
        // 与监控服务每次自检的前几步一致，各自都有「没有待修改就直接返回」的守卫。
        applyDuePendingChanges(context)

        CreditManager.saveBaselineBlacklist(context)
        TimeRuleManager.saveBaselineSnapshot(context)
        // 保护开关（防卸载、小窗增强、开发者选项拦截等）的基准只由 saveBaselineParams 写入。
        TimeRuleManager.saveBaselineParams(context)
        AppWindowManager.saveBaseline(context)
        prefs.edit().putLong(KEY_LAST_BASELINE_TIME, System.currentTimeMillis()).apply()
        Log.i(TAG, "Daily Baseline Snapshot Updated (bizDate=$currentBizDate)")
        return true
    }

    private fun applyDuePendingChanges(context: Context) {
        if (CreditManager.hasPendingApps(context)) {
            CreditManager.applyPendingAppsToActive(context)
        }
        TimeRuleManager.applyPendingToActive(context)
        if (AppWindowManager.hasPendingChanges(context)) {
            AppWindowManager.applyPendingToActive(context)
        }
        CreditManager.checkAndApplyPendingWeeklyLimit(context)
        // 保护开关的待关闭记录（ConfigPendingManager）在读取状态时按到期时间自行落地，无需在此处理。
    }
}
