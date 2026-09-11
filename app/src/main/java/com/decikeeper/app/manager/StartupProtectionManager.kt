/**
 * =================================================================================
 * [Context Header]
 * * @file: StartupProtectionManager.kt
 * * @role: Domain Skill
 * * @description: 重启与进程恢复阶段的快速锁屏保护策略。
 * * @responsibilities:
 * 1. 判定系统初始化快速锁屏是否应启动。
 * 2. 统一快速锁屏最短持有、最长兜底与主程序接管交接时机。
 * * @context:
 * - 上游: BootReceiver, MyApplication, CoreService, MonitorService
 * - 下游: FastLockService
 * * @constraints (必读):
 * 1. 禁止引入 UI 细节，仅输出启动保护策略。
 * 2. 时间计算必须纯函数化，便于 TestScenarioManager 断言。
 * 3. 必须处理重启早期无障碍已开启但尚未连接的安全窗口。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.decikeeper.app.R
import com.decikeeper.app.manager.AntiUninstallManager
import com.decikeeper.app.service.FastLockService



object StartupProtectionManager {
    const val ACTION_REQUEST_FAST_LOCK_HANDOFF_CLOSE =
        "com.decikeeper.app.action.REQUEST_FAST_LOCK_HANDOFF_CLOSE"
    const val ACTION_REFRESH_FAST_LOCK_HOLD =
        "com.decikeeper.app.action.REFRESH_FAST_LOCK_HOLD"
    const val FAST_LOCK_MIN_VISIBLE_MS = 10_000L
    const val FAST_LOCK_MAX_VISIBLE_MS = 10_000L
    const val CORE_HANDOFF_REQUEST_DELAY_MS = 4_000L
    const val EARLY_BOOT_FAST_LOCK_WINDOW_MS = 120_000L

    // MyApplication 已在本轮启动中拉过 FastLockService 时置 true，
    // BootReceiver 看到此标志就不再重复拉起，避免系统初始化界面出现两次。
    @Volatile var earlyBootFastLockStarted = false

    // MyApplication 直接 addView 的覆盖层，FastLockService 启动后接管。
    @Volatile var earlyBootOverlayView: View? = null
        private set
    @Volatile var earlyBootOverlayAttachedAtMs: Long = 0L
        private set
    @Volatile private var earlyBootWindowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var earlyBootCountdownRunnable: Runnable? = null
    private var earlyBootTimeoutRunnable: Runnable? = null

    /**
     * 在 MyApplication.onCreate() 中直接 addView，不等 Service 启动。
     * 返回 true 表示覆盖层已成功添加到 WindowManager。
     */
    fun attachEarlyBootFastLock(context: Context): Boolean {
        if (!shouldKeepInitialization(context)) {
            Log.i("DeciKeeper", "StartupProtectionManager: anti-uninstall initialization disabled, skip early boot overlay")
            return false
        }
        if (earlyBootOverlayView != null) return true

        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            earlyBootWindowManager = wm
            val themeKey = context.getSharedPreferences("decikeeper_ui_theme", Context.MODE_PRIVATE)
                .getString("theme", "spring") ?: "spring"
            val themeRes = when (themeKey) {
                "summer" -> R.style.Theme_DeciKeeper_Summer
                "autumn" -> R.style.Theme_DeciKeeper_Autumn
                "winter" -> R.style.Theme_DeciKeeper_Winter
                else -> R.style.Theme_DeciKeeper_Spring
            }
            val themedContext = android.view.ContextThemeWrapper(context, themeRes)
            val inflater = LayoutInflater.from(themedContext)
            val view = inflater.inflate(R.layout.window_fast_lock, null).apply {
                isClickable = true
                isFocusable = true
                setOnTouchListener { _, _ -> true }
            }

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams().apply {
                type = layoutFlag
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            wm.addView(view, params)
            earlyBootOverlayView = view
            val attachedAt = System.currentTimeMillis()
            earlyBootOverlayAttachedAtMs = attachedAt
            Log.d("DeciKeeper", "StartupProtectionManager: early boot overlay attached directly at ${earlyBootOverlayAttachedAtMs}")
            // [2026-06-25] 诊断日志：记录 attachEarlyBootFastLock 的调用方（MyApplication 还是 BootReceiver）
            // 用于排查"初始化界面刷新两次"现象（多次 attach）
            // 详见 Issue_AntiUninstallBootGap 尝试 8
            Log.i("DeciKeeper", "[FastLock] attach caller=${Thread.currentThread().stackTrace[4]}")

            // 启动独立主线程倒计时调度链，防止 Service 未能接管时秒数静止
            val countdownTv = view.findViewById<android.widget.TextView>(R.id.countdown_text)
            startEarlyBootCountdown(countdownTv, attachedAt, FAST_LOCK_MAX_VISIBLE_MS)

            // 设定最大超时安全兜底（10s），防止 Service 未启动或被系统拦截时永久卡死在屏幕上
            scheduleEarlyBootTimeout(context, FAST_LOCK_MAX_VISIBLE_MS)
            true
        } catch (e: Exception) {
            Log.e("DeciKeeper", "StartupProtectionManager: failed to attach early boot overlay", e)
            false
        }
    }

    /**
     * FastLockService 调用：接管 MyApplication 直接添加的覆盖层。
     * 返回被接管的 View，或 null 表示没有已存在的覆盖层。
     */
    fun takeOverEarlyBootOverlay(): View? {
        cancelEarlyBootTasks()
        earlyBootWindowManager = null
        val view = earlyBootOverlayView
        earlyBootOverlayView = null
        return view
    }

    /**
     * 兜底/主动关闭早期启动覆盖层（如 CoreService 启动就绪、超时到期等）。
     */
    fun dismissEarlyBootOverlay(context: Context? = null, reason: String = "manual") {
        mainHandler.post {
            cancelEarlyBootTasks()
            val view = earlyBootOverlayView ?: return@post
            val wm = earlyBootWindowManager ?: (context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            try {
                if (view.parent != null) {
                    wm?.removeView(view)
                }
                Log.i("DeciKeeper", "[FastLock] early boot overlay dismissed: reason=$reason")
            } catch (e: Exception) {
                Log.e("DeciKeeper", "[FastLock] failed to remove early boot overlay", e)
            } finally {
                earlyBootOverlayView = null
                earlyBootWindowManager = null
            }
        }
    }

    private fun cancelEarlyBootTasks() {
        earlyBootCountdownRunnable?.let { mainHandler.removeCallbacks(it) }
        earlyBootCountdownRunnable = null
        earlyBootTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        earlyBootTimeoutRunnable = null
    }

    private fun startEarlyBootCountdown(
        textView: android.widget.TextView?,
        attachedAtMs: Long,
        maxVisibleMs: Long
    ) {
        cancelEarlyBootTasks()
        if (textView == null) return
        val deadlineMs = attachedAtMs + maxVisibleMs
        val ceilingSeconds = ((maxVisibleMs + 999L) / 1000L).toInt()

        fun updateUi(): Boolean {
            val now = System.currentTimeMillis()
            val seconds = remainingCountdownSecondsFromDeadline(deadlineMs, now, ceilingSeconds)
            textView.text = "剩余 $seconds 秒"
            return seconds > 0
        }

        updateUi()

        val tick = object : Runnable {
            override fun run() {
                if (earlyBootOverlayView == null) return
                if (updateUi()) {
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }
        earlyBootCountdownRunnable = tick
        mainHandler.postDelayed(tick, 1000L)
    }

    private fun scheduleEarlyBootTimeout(context: Context, maxVisibleMs: Long) {
        earlyBootTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val timeoutRunnable = Runnable {
            if (earlyBootOverlayView != null) {
                Log.w("DeciKeeper", "[FastLock] early boot overlay reached max timeout, auto dismissing")
                dismissEarlyBootOverlay(context, "max_timeout")
            }
        }
        earlyBootTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, maxVisibleMs)
    }

    fun isEarlyBootOverlayAttached(): Boolean = earlyBootOverlayView != null

    /** 只有防卸载保护仍开启且系统无障碍仍开启时，才需要初始化覆盖层。 */
    fun shouldKeepInitialization(context: Context): Boolean {
        return AntiUninstallManager.shouldShowInitialization(
            AntiUninstallManager.getProtectionState(context)
        ) && PermissionManager.isAccessibilityEnabled(context)
    }

    fun shouldStartFastLock(
        isInSegment: Boolean,
        isAdaptation: Boolean,
        hasOverlay: Boolean,
        isA11yEnabled: Boolean
    ): Boolean {
        return isInSegment && !isAdaptation && hasOverlay && isA11yEnabled
    }

    fun shouldStartEarlyBootFastLock(
        uptimeMs: Long,
        isA11yEnabled: Boolean,
        bootWindowMs: Long = EARLY_BOOT_FAST_LOCK_WINDOW_MS
    ): Boolean {
        return uptimeMs in 0..bootWindowMs && isA11yEnabled
    }

    fun remainingFastLockHoldMs(
        startedAtMs: Long,
        requestAtMs: Long,
        minVisibleMs: Long = FAST_LOCK_MIN_VISIBLE_MS
    ): Long {
        val elapsedMs = (requestAtMs - startedAtMs).coerceAtLeast(0L)
        return (minVisibleMs - elapsedMs).coerceAtLeast(0L)
    }

    /**
     * 纯函数：计算 FastLock 覆盖层当前应显示的"剩余秒数"。
     *
     * 输入：
     * - [startedAtMs]：本次持有窗口的基准时刻（毫秒）。handoff / refresh 会重置它。
     * - [nowMs]：当前时刻（毫秒）。允许小于基准以处理测试或边缘。
     * - [maxVisibleMs]：覆盖层最长的可见上限。秒数显示按上限递减，到 0 后不再继续。
     *
     * 输出：当前应展示给用户的"剩余 N 秒"。当 elapsed >= maxVisibleMs 时返回 0，
     * 表示已超出 max timeout，覆盖层应被关闭。
     *
     * 设计要点：
     * - 纯函数，不持有 Android 状态，可在 JVM 单测中覆盖。
     * - 与 [remainingFastLockHoldMs]（minHold 维度）分离：minHold 决定实际关闭动作，
     *   此函数仅驱动 TextView 显示。
     * - 输入防御：nowMs < startedAtMs → 不返回负数。
     */
    fun remainingCountdownSeconds(
        startedAtMs: Long,
        nowMs: Long,
        maxVisibleMs: Long = FAST_LOCK_MAX_VISIBLE_MS
    ): Int {
        val elapsedMs = (nowMs - startedAtMs).coerceAtLeast(0L)
        val remainingMs = (maxVisibleMs - elapsedMs).coerceAtLeast(0L)
        // 向上取整，让秒数在跨秒前仍能正确显示一帧。例如 elapsed=999ms 显示 10 秒，符合 UI 直觉。
        val totalSeconds = (remainingMs + 999L) / 1000L
        return totalSeconds.coerceAtMost((maxVisibleMs + 999L) / 1000L).toInt().coerceAtLeast(0)
    }

    /**
     * 纯函数：基于 deadline 模型的剩余秒数（[2026-07-06 session 3 返工]）。
     *
     * 与 [remainingCountdownSeconds] 不同：直接以 deadlineMs 作为"剩余时间终点"，
     * 而不是从基准 + maxVisibleMs 推算。deadlineMs 是 countdown 显示 与 timeout 关闭
     * 的**单一来源**，避免之前 mStartedAtMs + maxVisibleMs 与 scheduleMaxTimeout()
     * 独立漂移的根因。
     *
     * 输入：
     * - [deadlineMs]：本窗口的绝对关闭时刻（毫秒）。
     * - [nowMs]：当前时刻（毫秒）。
     * - [ceilingMaxSeconds]：上限（秒），用于钳制显示范围。
     *
     * 输出：当前应展示的"剩余 N 秒"。deadlineMs <= nowMs → 0。
     */
    fun remainingCountdownSecondsFromDeadline(
        deadlineMs: Long,
        nowMs: Long,
        ceilingMaxSeconds: Int = (FAST_LOCK_MAX_VISIBLE_MS / 1000L).toInt()
    ): Int {
        if (deadlineMs <= 0L) return 0
        val remainingMs = (deadlineMs - nowMs).coerceAtLeast(0L)
        val totalSeconds = (remainingMs + 999L) / 1000L
        return totalSeconds.toInt().coerceAtMost(ceilingMaxSeconds).coerceAtLeast(0)
    }

    /**
     * 纯函数：基于 deadline 模型计算"距离关闭还有多少毫秒"。
     *
     * 替代原先基于 [remainingFastLockHoldMs] 的 minHold 推导逻辑。
     * 所有"真实关闭时机"统一读取 mDeadlineMs。
     *
     * 输入：
     * - [deadlineMs]：本窗口的绝对关闭时刻（毫秒）。
     * - [nowMs]：当前时刻（毫秒）。
     *
     * 输出：剩余毫秒（>= 0）。<= 0 表示已到关闭时刻。
     */
    fun remainingMsFromDeadline(deadlineMs: Long, nowMs: Long): Long {
        if (deadlineMs <= 0L) return 0L
        return (deadlineMs - nowMs).coerceAtLeast(0L)
    }

    /**
     * 纯函数：计算 adopt 路径的 deadline。
     *
     * adopt 表示覆盖层由 MyApplication / BootReceiver 提前 attach，
     * FastLockService 在 onCreate 时只是"接管"现有覆盖层。
     * 用户已经看到 overlay 真实起点 = earlyBootOverlayAttachedAtMs，
     * 所以 deadline 应从那个真实起点算起，而不是从 onCreate 时刻。
     *
     * 输入：
     * - [earlyBootOverlayAttachedAtMs]：MyApplication/BootReceiver 已 attach 的时刻。
     * - [nowMs]：当前时刻（FastLockService.onCreate 时刻，通常 >= earlyBootOverlayAttachedAtMs）。
     * - [maxVisibleMs]：覆盖层最大可见时长。
     *
     * 输出：deadline = earlyBootOverlayAttachedAtMs + maxVisibleMs。
     * 防御：若 earlyBootOverlayAttachedAtMs 未设置或异常，回退到 nowMs + maxVisibleMs。
     */
    fun computeAdoptDeadlineMs(
        earlyBootOverlayAttachedAtMs: Long,
        nowMs: Long,
        maxVisibleMs: Long = FAST_LOCK_MAX_VISIBLE_MS
    ): Long {
        // 用真实起点：overlay 第一次出现在用户眼前的那一刻
        val base = if (earlyBootOverlayAttachedAtMs > 0L) earlyBootOverlayAttachedAtMs else nowMs
        return base + maxVisibleMs
    }

    /**
     * 纯函数：计算 inflate 路径的 deadline。
     *
     * inflate 表示 FastLockService 自己 inflate window（无早期 overlay 可接管），
     * deadline = nowMs + maxVisibleMs。
     */
    fun computeInflateDeadlineMs(
        nowMs: Long,
        maxVisibleMs: Long = FAST_LOCK_MAX_VISIBLE_MS
    ): Long {
        return nowMs + maxVisibleMs
    }

    /**
     * 纯函数：refresh 路径的 deadline（保持单调递增）。
     *
     * refresh 时若希望开完整新窗口，deadline 必须 >= 当前 deadline（不缩短）；
     * 同时也 >= nowMs + maxVisibleMs（至少给用户完整 maxVisibleMs 时长）。
     *
     * 取 max(currentDeadline, nowMs + maxVisibleMs)，保证 refresh 后窗口不缩短。
     */
    fun computeRefreshDeadlineMs(
        currentDeadlineMs: Long,
        nowMs: Long,
        maxVisibleMs: Long = FAST_LOCK_MAX_VISIBLE_MS
    ): Long {
        val candidate = nowMs + maxVisibleMs
        return maxOf(currentDeadlineMs, candidate)
    }

    /**
     * 纯函数：handoff 路径的 deadline（允许缩短）。
     *
     * handoff 时 CoreService 已接管，FastLockService 只保留 minHold 余量就关闭，
     * 所以 deadline 显式缩短到 nowMs + delayMs。
     * 这是 countdown 与真实窗口对齐的关键：显示的就是真实剩余。
     */
    fun computeHandoffDeadlineMs(
        nowMs: Long,
        delayMs: Long
    ): Long {
        return nowMs + delayMs.coerceAtLeast(0L)
    }

    /**
     * 纯函数：onStartCommand 收到 EXTRA_MAX_VISIBLE_MS 时的 deadline 更新。
     *
     * - 新 maxVisibleMs >= 当前 maxVisibleMs（refresh-like）：不缩短。
     * - 新 maxVisibleMs < 当前 maxVisibleMs：缩短到新窗口。
     */
    fun computeOverrideDeadlineMs(
        currentDeadlineMs: Long,
        nowMs: Long,
        newMaxVisibleMs: Long,
        oldMaxVisibleMs: Long = FAST_LOCK_MAX_VISIBLE_MS
    ): Long {
        // 防御：负数 maxVisibleMs 直接当成"不变化"
        if (newMaxVisibleMs <= 0L) return currentDeadlineMs
        // 缩短场景：新 deadline 从 nowMs 重新计
        if (newMaxVisibleMs < oldMaxVisibleMs) {
            return nowMs + newMaxVisibleMs
        }
        // 扩大或不变场景：max(当前 deadline, nowMs + newMax) — 不缩短
        return maxOf(currentDeadlineMs, nowMs + newMaxVisibleMs)
    }

    fun buildFastLockIntent(context: Context, maxVisibleMs: Long = FAST_LOCK_MAX_VISIBLE_MS): Intent {
        return Intent(context, FastLockService::class.java).apply {
            // [2026-07-06] 问题 3 (Codex review):把 maxVisibleMs 通过 extra 传入 FastLockService,
            // 让服务用真实持有窗口计算 countdown,而不是 hardcode 10s。
            // 启动路径(BootReceiver/MyApplication):默认 10s。
            // config-change 路径(CoreService):传 10s。
            putExtra(FastLockService.EXTRA_MAX_VISIBLE_MS, maxVisibleMs)
            if (FastLockService.isRunning) {
                action = ACTION_REFRESH_FAST_LOCK_HOLD
            }
        }
    }

    fun buildFastLockHandoffIntent(context: Context): Intent {
        return Intent(context, FastLockService::class.java).apply {
            action = ACTION_REQUEST_FAST_LOCK_HANDOFF_CLOSE
        }
    }

    fun startFastLock(context: Context, maxVisibleMs: Long = FAST_LOCK_MAX_VISIBLE_MS) {
        if (!shouldKeepInitialization(context)) {
            Log.i("DeciKeeper", "StartupProtectionManager: anti-uninstall initialization disabled, skip FastLock service")
            return
        }
        // [2026-07-06 REQ-2 诊断] 记录 startFastLock 入口上下文：
        // - 调用方（栈追溯）— 用于定位是哪个组件触发（划掉任务 / config-change / boot）
        // - 传入 maxVisibleMs — 标识期望的窗口上限
        // - FastLockService.isRunning — 标识是否会走 ACTION_REFRESH_FAST_LOCK_HOLD 路径
        //   （buildFastLockIntent 内 isRunning=true 会把 action 改为 REFRESH）
        // 注：当前 FastLockService 实例持有的 mDeadlineMs 是 private 字段，
        // 无法从此入口直接读取；详细 deadline 变化见 FastLockService.onStartCommand 日志。
        val callerTrace = Thread.currentThread().stackTrace
        val caller = callerTrace.getOrNull(4)?.toString() ?: "unknown"
        val willRefresh = FastLockService.isRunning
        Log.i(
            "DeciKeeper",
            "[FastLockEntry] startFastLock caller=$caller " +
                "maxVisibleMs=$maxVisibleMs " +
                "fastLockRunning=$willRefresh " +
                "now=${System.currentTimeMillis()}"
        )
        val intent = buildFastLockIntent(context, maxVisibleMs)
        // [2026-07-06 REQ-2 诊断] 记录本次下发到 FastLockService 的 intent 是否带 EXTRA_MAX_VISIBLE_MS，
        // 以及最终被 buildFastLockIntent 选中的 action（new vs REFRESH）。
        Log.i(
            "DeciKeeper",
            "[FastLockEntry] intent built action=${intent.action ?: "<new>"} " +
                "extraMaxVisible=${intent.getLongExtra(FastLockService.EXTRA_MAX_VISIBLE_MS, -1L)}"
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e("DeciKeeper", "[FastLockEntry] startService failed", e)
        }
    }

    fun requestFastLockHandoff(context: Context) {
        if (!FastLockService.isRunning) return
        val intent = buildFastLockHandoffIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopFastLock(context: Context) {
        dismissEarlyBootOverlay(context, "stop_fast_lock")
        context.stopService(buildFastLockIntent(context))
    }
}
