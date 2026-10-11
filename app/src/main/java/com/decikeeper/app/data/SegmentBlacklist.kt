package com.decikeeper.app.data

import android.content.Context
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.TimeRuleManager



/**
 * 勿扰时段独立黑名单的「硬剔除 + 有效名单」判定（SPEC §2 / §7，PLAN §1 固化接口）。
 *
 * 唯一语义（消费者不得重复实现）：
 *   - listApps == null  → 继承态：运行时实时取全局黑名单。
 *   - listApps != null（含空列表）→ 脱钩态：本时段只用该列表（空 = 不锁任何）。
 *   - 硬剔除永远优先：无论名单内容，HARD_EXEMPT 内应用永不锁、永不显示在名单 UI。
 */
object SegmentBlacklist {

    const val LIST_MODE_BLACKLIST = "blacklist"
    const val LIST_MODE_WHITELIST = "whitelist"

    /**
     * 名单页可勾选的全集：所有带启动入口的应用（与 AppSelectionActivity.loadInstalledApps 一致）。
     * 白名单模式的补集以此为准。
     */
    fun allLaunchablePackages(context: Context): Set<String> {
        val pm = (context.applicationContext ?: context).packageManager
        val now = System.currentTimeMillis()
        launchableCache?.let { cached ->
            if (cached.owner.get() === pm && now - cached.at in 0 until LAUNCHABLE_CACHE_TTL_MS) return cached.packages
        }
        val computed = computeLaunchablePackages(pm)
        // 空集可能是包可见性受限 / 一次性失败，不缓存，下次重试
        if (computed.isNotEmpty()) launchableCache = LaunchableCache(java.lang.ref.WeakReference(pm), now, computed)
        return computed
    }

    /** 安装 / 卸载应用后让缓存失效（名单页刷新、测试装配包时调用）。 */
    fun invalidateLaunchableCache() {
        launchableCache = null
    }

    /**
     * 预热缓存。全集要对每个已装应用逐个问一次系统（几百次 IPC，慢机型上秒级），
     * 保存时段 / 切换生效策略的不利分析在主线程上会反复用到它，所以进入时间规则页时先在后台算好。
     */
    fun prewarmLaunchablePackages(context: Context) {
        val appContext = context.applicationContext ?: return
        Thread({ runCatching { allLaunchablePackages(appContext) } }, "launchable-prewarm")
            .apply { isDaemon = true }
            .start()
    }

    private fun computeLaunchablePackages(pm: android.content.pm.PackageManager): Set<String> {
        return runCatching {
            pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                .filter { runCatching { pm.getLaunchIntentForPackage(it.packageName) != null }.getOrDefault(false) }
                .map { it.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private const val LAUNCHABLE_CACHE_TTL_MS = 60_000L

    private class LaunchableCache(
        val owner: java.lang.ref.WeakReference<android.content.pm.PackageManager>,
        val at: Long,
        val packages: Set<String>
    )

    @Volatile
    private var launchableCache: LaunchableCache? = null

    /**
     * 把用户在名单页勾选的集合（白名单模式=允许列表）转成「有效黑名单集合」。
     * 存储层始终以「有效黑名单」为准：白名单模式取补集（全集 − 勾选 − 硬豁免），
     * 因此判定（isSubjectToBlock）与不利分析（扩/缩）无需感知模式。
     */
    fun toEffectiveBlockedSet(context: Context, mode: String, selection: Set<String>): Set<String> {
        return if (mode == LIST_MODE_WHITELIST) {
            allLaunchablePackages(context)
                .filterNot { selection.contains(it) || isHardExempt(context, it) }
                .toSet()
        } else {
            selection.filterNot { isHardExempt(context, it) }.toSet()
        }
    }

    /** 把「有效黑名单集合」转回名单页勾选集合（白名单模式取补集，供 UI 展示）。 */
    fun toDisplaySelection(context: Context, mode: String, blockedSet: Set<String>): Set<String> {
        return if (mode == LIST_MODE_WHITELIST) {
            allLaunchablePackages(context)
                .filterNot { blockedSet.contains(it) || isHardExempt(context, it) }
                .toSet()
        } else {
            blockedSet.filterNot { isHardExempt(context, it) }.toSet()
        }
    }

    /**
     * 硬剔除：这些应用永不可被「本时段名单」锁定（判定与 UI 共用）。
     *
     * 保守匹配，宁多勿漏——漏配会导致用户把电话/短信锁死（红线）。
     * 只决定「名单锁不到它」，不改变现有 isSafe / 特权 / 自由时间逻辑。
     */
    fun isHardExempt(context: Context, pkg: String): Boolean {
        val p = pkg.lowercase()
        // 决定守护 自身
        if (p == context.packageName.lowercase()) return true
        // 桌面 launcher
        if (CreditManager.isLauncher(p)) return true
        // 设置
        if (p == "com.android.settings") return true
        // 输入法
        if (CreditManager.isInputMethod(context, p)) return true
        // 系统导航 / SystemUI：手势导航栏、状态栏、最近任务等系统组件，永不锁（红线）。
        // vivo 的 systemuiplugin 属于同一系统界面体系，一并豁免。
        // com.vivo.upslide = vivo「系统导航」(System navigation) 上滑手势服务（2026-08-15 用户报告仍在名单中）。
        if (p == "com.android.systemui" || p.contains("systemui") || p == "com.vivo.systemuiplugin" ||
            p == "com.vivo.upslide") return true
        // 系统跟踪（开发者工具 System Tracing）：系统组件，永不锁。
        if (p == "com.android.traceur") return true
        // 闹钟 / 时钟：系统级（vivo BBK 时钟、AOSP 桌面时钟、vivo 梦境时钟、关机闹钟），永不锁。
        if (p == "com.android.bbkclock" || p == "com.android.deskclock" || p.contains("deskclock") ||
            p == "com.vivo.dream.clock" || p == "com.qualcomm.qti.poweroffalarm") return true
        // 电话 / 拨号 / 联系人
        if (p == "com.android.phone" ||
            p.contains("dialer") ||
            p.contains("incallui") ||
            p.contains("telecom") ||
            p.contains("contacts")
        ) return true
        // 短信 / 信息
        if (p.contains("messag") || p.contains("mms") || p.contains("sms")) return true
        return false
    }

    /** 本时段有效名单 = (segment.listApps ?: 全局黑名单) − 硬剔除 */
    fun effectiveList(context: Context, segment: TimeSegment): Set<String> {
        val raw = segment.listApps ?: CreditManager.getBlacklistApps(context)
        return raw.filterNot { isHardExempt(context, it) }.toSet()
    }

    /** 是否命中本时段有效名单（含硬剔除过滤） */
    fun isInEffectiveList(context: Context, segment: TimeSegment, pkg: String): Boolean {
        return effectiveList(context, segment).any { it.equals(pkg, ignoreCase = true) }
    }

    /**
     * 【Phase 3 解耦】"是否受本时段名单拦截"的**唯一语义来源**（勿扰锁判定 / billing /
     * 重锁路径共用，消费者不得各自重复实现）：
     *   - 有时段时（无论是脱钩态还是继承态），均按该时段有效名单判定（effectiveList 内部已处理脱钩与全局黑名单继承）；
     *   - 空档期（currentSeg == null）时，按全局黑名单并排除硬豁免应用判定；
     *   - 绝不将「应用时间窗管理应用」误判为勿扰黑名单（时间窗由 AppWindowManager 单独管控）。
     */
    fun isSubjectToBlock(context: Context, currentSeg: TimeSegment?, pkg: String): Boolean {
        return if (currentSeg != null) {
            isInEffectiveList(context, currentSeg, pkg)
        } else {
            !isHardExempt(context, pkg) && CreditManager.isAppOnBlacklist(context, pkg)
        }
    }

    /**
     * 锁屏 / 重锁 / 临时解锁路径的统一判定（自行取当前时段后委托 isSubjectToBlock）。
     */
    fun shouldLock(context: Context, pkg: String): Boolean {
        return isSubjectToBlock(context, TimeRuleManager.getCurrentSegment(context), pkg)
    }

    /**
     * TASK-03 billing 一致性（SPEC §2.7 / §6 S7）：本时段有效名单命中才计费。
     * 名单命中语义委托 isSubjectToBlock（不重复实现），
     * 再叠加：勿扰时段内（isInSegment）、非自由时间（!isFreeTime）、60s 节流。
     *
     * 与原 MonitorService billing 内联条件逐位等价：
     *   isBlacklisted && isInSegment && !isFreeTime && (now - lastBillMs) >= 60000。
     */
    fun shouldBill(
        context: Context,
        currentSeg: TimeSegment?,
        pkg: String,
        isInSegment: Boolean,
        isFreeTime: Boolean,
        lastBillMs: Long,
        nowMs: Long
    ): Boolean {
        val isBlacklisted = isSubjectToBlock(context, currentSeg, pkg)
        return isBlacklisted && isInSegment && !isFreeTime && nowMs - lastBillMs >= 60_000L
    }
}
