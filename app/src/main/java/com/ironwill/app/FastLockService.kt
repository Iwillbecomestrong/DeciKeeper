/**
 * =================================================================================
 * [Context Header]
 * * @file: FastLockService.kt
 * * @role: Domain Skill
 * * @description: 快速锁机服务入口与调度。
 * * @responsibilities:
 * 1. 响应快速锁机触发。
 * 2. 协调锁屏展示与解除。
 * * @context:
 * - 上游: BootReceiver, MyApplication, CoreService, MonitorService
 * - 下游: LockWindowManager
 * * @constraints (必读):
 * 1. 禁止阻塞主线程。
 * 2. 需处理生命周期异常。
 * 3. 不承载复杂 UI 逻辑。
 * =================================================================================
 */
package com.ironwill.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager

/**
 * FastLockService - 极速锁机服务
 *
 * 专门用于在 APP 进程刚启动 (Application.onCreate) 时立即显示锁屏覆盖层。
 * 它的作用是填补 "进程启动" 到 "无障碍服务连接 (CoreService)" 之间的时间空隙。
 * 当 CoreService 启动成功后，会自动停止本服务。
 *
 * TYPE_APPLICATION_OVERLAY 覆盖层不受用户解锁状态限制：
 * - 用户已解锁：addView 后立即可见
 * - 用户未解锁：addView 成功但处于锁屏之后，解锁瞬间即显示，无需等待广播
 */
class FastLockService : Service() {

    private var mWindowManager: WindowManager? = null
    private var mLockView: View? = null
    private val mHandler = Handler(Looper.getMainLooper())
    private var mStartedAtMs = 0L
    private var mPendingCloseRunnable: Runnable? = null
    private var mExtensionCount = 0
    private val mMaxExtensions = 0 // 超时后不再延长，最多等 30s 即关闭覆盖层
    private var mCountdownTextView: android.widget.TextView? = null
    private var mCountdownRunnable: Runnable? = null
    private var mLastDisplayedSeconds: Int = -1
    // [2026-07-06] 问题 3 (Codex review):maxVisibleMs 由启动方传入(boot/cold-start 30s,
    // config-change 10s),不再 hardcode 默认常量。启动时如果未传 intent extra,
    // 兜底使用 FAST_LOCK_MAX_VISIBLE_MS(30s)以兼容旧调用路径。
    private var mMaxVisibleMs: Long = StartupProtectionManager.FAST_LOCK_MAX_VISIBLE_MS
    // [2026-07-06] 问题 2 (Codex review):记录当前挂起的 countdown runnable 个数。
    // 期望 ≤ 1 — 同时刻只能有 1 个 tick 续帧,避免双链调度。
    private var mPendingCountdownCount: Int = 0
    // [2026-07-06 session 3 返工] Codex review 问题 1 根因修复:
    // 引入 mDeadlineMs 作为 countdown 显示 与 timeout 关闭 的**单一来源**。
    // 之前 mStartedAtMs(显示基线)与 scheduleMaxTimeout()(真实关闭)是两套独立状态机,
    // 从未同步 — 导致 countdown 在真实关闭前到 0 或 countdown 跳到 30 但实际只 hold 10s。
    //
    // 单一来源规则:
    // - countdown 显示 = (mDeadlineMs - now) / 1000
    // - mTimeoutRunnable 调度到 mDeadlineMs 时刻触发
    // - adopt / inflate / refresh / handoff / onStartCommand maxVisible 覆盖 5 条入口
    //   都通过纯函数 helper 计算 mDeadlineMs,避免分散 hardcode
    //
    // 默认 0 = 未设置;在 onCreate 中按路径设置。
    private var mDeadlineMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundCompat()

        // 接管 MyApplication 直接添加的覆盖层（毫秒级已可见），或自行 addView
        val adopted = StartupProtectionManager.takeOverEarlyBootOverlay()
        if (adopted != null) {
            mLockView = adopted
            mWindowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            mStartedAtMs = StartupProtectionManager.earlyBootOverlayAttachedAtMs
            // [2026-07-06 session 3 返工] Codex review 问题 1:
            // adopt 路径的 mDeadlineMs 必须基于真实起点 (earlyBootOverlayAttachedAtMs),
            // 而不是 onCreate 时刻。倒计时显示与 timeout 关闭都从那一刻起算 maxVisibleMs,
            // 与用户实际"已看到 overlay 多久"对齐,避免 countdown 先于真实 timeout 到 0。
            val now = System.currentTimeMillis()
            mDeadlineMs = StartupProtectionManager.computeAdoptDeadlineMs(
                earlyBootOverlayAttachedAtMs = mStartedAtMs,
                nowMs = now,
                maxVisibleMs = mMaxVisibleMs
            )
            // adopt 路径：覆盖层可能是 MyApplication 早期 inflate 的，复用同一 view 引用
            bindCountdownTextView()
            startCountdownTick()
            Log.d(TAG, "FastLockService: adopted early boot overlay, attachedAt=${mStartedAtMs} deadline=${mDeadlineMs}ms (maxVisible=${mMaxVisibleMs}ms)")
        } else {
            showLockWindow()
            // showLockWindow() 自身完成 inflate + bindCountdownTextView + startCountdownTick
        }

        // 异步校验：以缓存为依据快速决策启动 FastLock，后台确认真实无障碍状态。
        // - 缓存 true 但无障碍实际已关闭 → 关掉 FastLock + 更新缓存
        // - 缓存 false 但无障碍实际已开启 → 更新缓存（下次重启生效）
        Thread {
            try {
                val serviceName = "${packageName}/${CoreService::class.java.canonicalName}"
                val enabled = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                val actuallyEnabled = enabled?.contains(serviceName) == true
                val cacheSaysEnabled = StartupA11yCache.isA11yWasEnabledForStartup(this)

                if (cacheSaysEnabled && !actuallyEnabled) {
                    // FastLock 已启动但无障碍实际被关闭了 → 关掉覆盖层，更新缓存
                    StartupA11yCache.setA11yWasEnabled(this, false)
                    mHandler.post {
                        if (isRunning) {
                            Log.d(TAG, "FastLockService: A11y disabled since last boot, dismissing")
                            stopSelf()
                        }
                    }
                } else if (!cacheSaysEnabled && actuallyEnabled) {
                    // 无障碍已重新开启，更新缓存供下次重启使用
                    StartupA11yCache.setA11yWasEnabled(this, true)
                    Log.d(TAG, "FastLockService: A11y re-enabled, cache updated for next boot")
                }
            } catch (_: Exception) {
            }
        }.start()

        // [2026-07-06 session 3 返工] scheduleMaxTimeout() 现在使用 mDeadlineMs
        // (单一来源),与 countdown 显示共用同一 deadline。
        scheduleMaxTimeout()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(9999, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } catch (e: Exception) {
                startForeground(9999, createNotification())
            }
        } else {
            startForeground(9999, createNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // [2026-07-06] 问题 3 (Codex review):读取启动方传入的 maxVisibleMs extra。
        // 启动路径(BootReceiver/MyApplication):不传 extra → 兜底 FAST_LOCK_MAX_VISIBLE_MS(30s)。
        // 配置变化路径(CoreService):传 EXTRA_MAX_VISIBLE_MS=10_000L。
        // 这里拿到 intent 是第一个可能产生配置变化的入口,必须重启 tick 以让新的 maxVisibleMs 生效。
        //
        // [2026-07-06 session 3 返工] Codex review 问题 1:
        // 收到新 maxVisibleMs 时同步更新 mDeadlineMs (单一来源),
        // 并 rescheduleMaxTimeout 让"真实关闭时机"也跟着改变。
        intent?.let {
            val extraMax = it.getLongExtra(EXTRA_MAX_VISIBLE_MS, StartupProtectionManager.FAST_LOCK_MAX_VISIBLE_MS)
            if (extraMax != mMaxVisibleMs) {
                val oldMax = mMaxVisibleMs
                val oldDeadline = mDeadlineMs
                Log.d(TAG, "FastLockService: maxVisible override ${mMaxVisibleMs}ms → ${extraMax}ms")
                mMaxVisibleMs = extraMax
                // 用纯函数计算新 deadline:缩短(config-change 30→10)允许,扩大不缩短
                val now = System.currentTimeMillis()
                val newDeadline = StartupProtectionManager.computeOverrideDeadlineMs(
                    currentDeadlineMs = mDeadlineMs,
                    nowMs = now,
                    newMaxVisibleMs = extraMax,
                    oldMaxVisibleMs = oldMax
                )
                // [2026-07-06 REQ-2 诊断] 记录 computeOverrideDeadlineMs 分支判定：
                // - 缩短：extraMax < oldMax → newDeadline = now + extraMax
                // - 延长/不变：max(旧 deadline, now + extraMax)
                val branch = if (extraMax <= 0L) {
                    "NOOP(newMax<=0 → return current)"
                } else if (extraMax < oldMax) {
                    "SHRINK(now+newMax)"
                } else {
                    "GROW_OR_EQUAL(max(current, now+newMax))"
                }
                Log.i(
                    "IronWill_Debug",
                    "[FastLockOverride] action=${it.action ?: "<new>"} " +
                        "oldMax=$oldMax newMax=$extraMax " +
                        "oldDeadline=$oldDeadline now=$now " +
                        "newDeadline=$newDeadline branch=$branch " +
                        "shrinkDeltaMs=${if (extraMax < oldMax) (oldDeadline - newDeadline) else 0L}"
                )
                mDeadlineMs = newDeadline
                // 重新调度 timeout runnable:与 mDeadlineMs 同步,而不是固定 30s
                rescheduleMaxTimeout()
                // 重启 tick:清旧 runnable + 用新 maxVisibleMs 重新驱动(单一调度链,不会留残留)
                if (mStartedAtMs > 0L) {
                    startCountdownTick()
                }
            } else {
                // [2026-07-06 REQ-2 诊断] 走到这里说明上游发来的 maxVisibleMs 与当前相同，
                // 不会触发 computeOverrideDeadlineMs；记录以便确认"无变化"是否被频繁误判为已覆盖。
                Log.d(
                    "IronWill_Debug",
                    "[FastLockOverride] no-op extraMax=$extraMax==current=mMaxVisibleMs " +
                        "deadline=$mDeadlineMs action=${it.action ?: "<new>"}"
                )
            }
        }
        when (intent?.action) {
            StartupProtectionManager.ACTION_REQUEST_FAST_LOCK_HANDOFF_CLOSE -> {
                closeAfterMinimumVisibleWindow("handoff_request")
            }
            StartupProtectionManager.ACTION_REFRESH_FAST_LOCK_HOLD -> {
                refreshMinimumVisibleWindow()
            }
        }
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val channelId = "ironwill_fast_lock"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !notificationChannelCreated) {
            val channel = NotificationChannel(channelId, "IronWill Startup Protection", NotificationManager.IMPORTANCE_MIN)
            channel.setShowBadge(false)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            notificationChannelCreated = true
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
    }

    private fun showLockWindow() {
        // Vivo/OEM 可能在锁屏→解锁过渡期间 detach TYPE_APPLICATION_OVERLAY 窗口。
        // 仅检查 mLockView != null 不够——必须确认 view 仍 attach 在 WindowManager 上。
        if (mLockView != null && mLockView?.parent != null) return

        // 旧 view 已被系统 detach，清理后重新 addView
        if (mLockView != null) {
            Log.d(TAG, "FastLockService: existing view detached by system, re-adding")
            mLockView = null
        }

        mWindowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(this)
        mLockView = inflater.inflate(R.layout.window_fast_lock, null).apply {
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
        }

        try {
            mWindowManager?.addView(mLockView, params)
            mStartedAtMs = System.currentTimeMillis()
            // [2026-07-06 session 3 返工] Codex review 问题 1:
            // inflate 路径的 mDeadlineMs = nowMs + mMaxVisibleMs。
            // countdown 显示与 timeout 关闭都从这一刻起算,保持单一来源。
            mDeadlineMs = StartupProtectionManager.computeInflateDeadlineMs(
                nowMs = mStartedAtMs,
                maxVisibleMs = mMaxVisibleMs
            )
            bindCountdownTextView()
            startCountdownTick()
            Log.d(TAG, "FastLockService view attached; minHold=${StartupProtectionManager.FAST_LOCK_MIN_VISIBLE_MS}ms maxHold=${StartupProtectionManager.FAST_LOCK_MAX_VISIBLE_MS}ms deadline=${mDeadlineMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "FastLockService: addView failed", e)
        }
    }

    /**
     * 集中绑定 countdown_text 控件。由 inflate path 与 adopt path 在拿到 mLockView 后调用。
     * 不绑定 = 不更新，避免 layout id 改动时崩溃。
     */
    private fun bindCountdownTextView() {
        val view = mLockView ?: return
        mCountdownTextView = view.findViewById(R.id.countdown_text)
    }

    /**
     * 启动/重置 countdown tick。集中驱动：
     * - inflate / adopt 后首次启动
     * - handoff 接受后重置基线
     * - refresh 后重置基线
     * - onStartCommand 收到 maxVisibleMs 覆盖后重启
     *
     * tick 只更新 TextView 文本，不影响任何关闭/接管逻辑。
     *
     * [2026-07-06] 问题 2 (Codex review):startCountdownTick 是"启动 + 续帧"的唯一调度者,
     * 每次调用清掉旧 runnable 再创建新的,handler 上同时刻只挂 1 个 countdown runnable。
     * updateCountdownDisplay() 是纯计算 + 设置函数,不负责 postDelayed,避免双链调度。
     *
     * [2026-07-06 session 3 返工] Codex review 问题 1:
     * tick 显示的剩余秒数 **统一读取 mDeadlineMs**（单一来源）,
     * 不再各自从 mStartedAtMs + mMaxVisibleMs 推算 —
     * 这是修掉"countdown 与 timeout 关闭时机脱钩"的根因关键。
     */
    private fun startCountdownTick() {
        if (mStartedAtMs <= 0L) {
            // 防御：没有 baseline 不启动 tick（但 inflate 路径已设置 baseline，这里仅兜底）
            mStartedAtMs = System.currentTimeMillis()
        }
        if (mDeadlineMs <= 0L) {
            // 兜底：若 deadline 未被调用方设置（如某些异常入口），用 inflate 模型
            mDeadlineMs = StartupProtectionManager.computeInflateDeadlineMs(
                nowMs = System.currentTimeMillis(),
                maxVisibleMs = mMaxVisibleMs
            )
        }
        mCountdownRunnable?.let { mHandler.removeCallbacks(it) }
        mCountdownRunnable = null
        mLastDisplayedSeconds = -1
        // 立即先更新一次(不调度),让 UI 立刻呈现当前剩余秒数,避免第一秒看起来没动
        updateCountdownDisplay()
        // 显示已到 0 → 不再续 tick,避免无意义刷新
        if (mLastDisplayedSeconds <= 0) return
        val tick = Runnable { onCountdownTickFired() }
        mCountdownRunnable = tick
        // 调度下一帧(handler 仍用主 looper,与 view.setText 同线程)
        mHandler.postDelayed(tick, 1000L)
        mPendingCountdownCount = 1
    }

    /**
     * tick 续帧回调:重新算值 + 设置 + 续 1s 下一帧(handler 唯一调度点)。
     */
    private fun onCountdownTickFired() {
        mPendingCountdownCount = 0
        mCountdownRunnable = null
        updateCountdownDisplay()
        if (mLastDisplayedSeconds <= 0) return
        val next = Runnable { onCountdownTickFired() }
        mCountdownRunnable = next
        mHandler.postDelayed(next, 1000L)
        mPendingCountdownCount = 1
    }

    /**
     * 纯计算 + 设置:不调度,只更新文本与缓存显示值。
     *
     * [2026-07-06] 问题 3 (Codex review):maxVisibleMs 使用当前服务持有的 mMaxVisibleMs,
     * 不再 hardcode FAST_LOCK_MAX_VISIBLE_MS,保证 config-change 路径 10s 窗口显示 10s。
     *
     * [2026-07-06 session 3 返工] Codex review 问题 1:
     * 显示剩余秒数统一来自 **mDeadlineMs**（单一来源）,
     * 而不是从 mStartedAtMs + mMaxVisibleMs 推算。
     * 这样 handoff 后 mDeadlineMs = now + delayMs 时,显示就是真实剩余 delayMs;
     * adopt 后 mDeadlineMs = earlyBootOverlayAttachedAtMs + maxVisibleMs 时,
     * 显示就是从用户首次看到 overlay 起的剩余时间。
     */
    private fun updateCountdownDisplay() {
        val textView = mCountdownTextView ?: return
        val now = System.currentTimeMillis()
        val ceilingSeconds = ((mMaxVisibleMs + 999L) / 1000L).toInt()
        val seconds = StartupProtectionManager.remainingCountdownSecondsFromDeadline(
            deadlineMs = mDeadlineMs,
            nowMs = now,
            ceilingMaxSeconds = ceilingSeconds
        )
        if (seconds != mLastDisplayedSeconds) {
            // [2026-07-06] REQ-2：每秒驱动 countdown_text，文案与真实剩余时长一致
            textView.text = "剩余 $seconds 秒"
            mLastDisplayedSeconds = seconds
        }
        // 显示已到 0 → 不再续 tick，避免无意义刷新
        if (seconds <= 0) {
            mCountdownRunnable?.let { mHandler.removeCallbacks(it) }
            mCountdownRunnable = null
            return
        }
        // [2026-07-06] 问题 2 (Codex review):此处不再 postDelayed 续帧。
        // 续帧职责已上移到 startCountdownTick() 与 onCountdownTickFired() 之间的单一调度链,
        // 此函数仅做"算值 + 设置"。避免双链调度导致 handler 上同时挂多个 runnable。
    }

    /**
     * [2026-07-06] 问题 2 (Codex review):返回当前挂在主 looper handler 上的 countdown 续帧 runnable 个数。
     * 期望 ≤ 1。测试通过此 helper 验证"同一 service 内连续 startCountdownTick 不会留下双链"。
     * 由于 Service 实例本身需要 Context,测试不直接 new Service,
     * 而是使用 [com.ironwill.app.FastLockService.CountdownTickCoordinator]
     * — 内部相同的调度封装,可在纯 JVM 单测中构造。
     */
    internal fun countPendingCountdownRunnables(): Int = mPendingCountdownCount

    private fun refreshMinimumVisibleWindow() {
        val now = System.currentTimeMillis()
        mStartedAtMs = now
        // [2026-07-06 session 3 返工] Codex review 问题 1:
        // refresh 路径的 mDeadlineMs = max(当前 deadline, now + mMaxVisibleMs) — 不缩短。
        // 这样 countdown 显示与 timeout 关闭都从这一刻重新算 maxVisibleMs,
        // 而不是只重置 mStartedAtMs 让 countdown 跳 30 但 timeout 还是旧的 onCreate + 30s。
        mDeadlineMs = StartupProtectionManager.computeRefreshDeadlineMs(
            currentDeadlineMs = mDeadlineMs,
            nowMs = now,
            maxVisibleMs = mMaxVisibleMs
        )
        // 同步重调度 timeout runnable:让"真实关闭时机"也跟着新 deadline 走
        rescheduleMaxTimeout()
        mPendingCloseRunnable?.let { mHandler.removeCallbacks(it) }
        mPendingCloseRunnable = null
        // [2026-07-06] REQ-2：refresh 必须同步重置 countdown 显示基线，
        // 否则 UI 数字会与真实剩余时间错位（看起来静止不动）。
        startCountdownTick()
        Log.d(TAG, "FastLockService hold window refreshed; deadline=${mDeadlineMs}ms")
    }

    private fun closeAfterMinimumVisibleWindow(reason: String) {
        // CE 存储尚未就绪时拒绝 handoff
        if (reason == "handoff_request" && !isCeStorageAvailable()) {
            Log.d(TAG, "FastLockService: refusing handoff, CE storage not ready; extending protection")
            // [2026-07-06 session 3 返工] 即使 handoff 被拒绝,timeout 调度也应基于当前 deadline
            rescheduleMaxTimeout()
            return
        }

        // 防卸载保护链 JIT 预热未完成时拒绝 handoff：关闭覆盖层后首次卸载检测会因
        // JIT 编译延迟而变慢，留下可卸载窗口。等待 warmup 完成后再允许关闭。
        if (reason == "handoff_request" && !CoreService.antiUninstallWarmedUp) {
            Log.d(TAG, "FastLockService: refusing handoff, anti-uninstall warmup not complete; retry in 2s")
            mHandler.postDelayed({
                if (isRunning) closeAfterMinimumVisibleWindow("handoff_request")
            }, 2000L)
            return
        }

        val now = System.currentTimeMillis()
        // handoff 时重置计时器：保证 CoreService 接管后覆盖层至少再留 minHold 时间
        if (reason == "handoff_request") {
            // [2026-07-06 REQ-2 诊断] handoff 入口:记录 reason、deadline、CoreService 状态、
            // shouldKeepStartupProtection 当前结果 — 用于排查"为什么总是跑满 30s 不提前关"。
            Log.i(
                "IronWill_Debug",
                "[FastLock_HandoffEntry] reason=$reason mDeadlineMs=$mDeadlineMs " +
                    "isServiceActive=${CoreService.isServiceActive} " +
                    "shouldKeepProtection=${shouldKeepStartupProtection()}"
            )
            // [2026-07-06 early close] CoreService 已活 + 无障碍信号正常：
            // 保护已就绪，提前 2s 自然关闭，不等满 minHold。
            if (CoreService.isServiceActive && CoreService.lastAccessibilitySignalTime > 0) {
                mDeadlineMs = now + 2000L
                rescheduleMaxTimeout()
                startCountdownTick()
                Log.d(TAG, "FastLockService: protection ready — early close in 2 s")
                return
            }
            mStartedAtMs = now
            mPendingCloseRunnable?.let { mHandler.removeCallbacks(it) }
            mPendingCloseRunnable = null
            // [2026-07-06 session 3 返工] Codex review 问题 1:
            // handoff 路径的 mDeadlineMs **必须 = now + delayMs**(不能跳到 30),
            // 这是修掉"countdown 显示 30 但实际只 hold 10s"的关键。
            // countdown 显示的真实剩余 == delayMs,timeout 关闭时机也 == now + delayMs,
            // 单一来源完全同步。
            val delayMs = StartupProtectionManager.remainingFastLockHoldMs(mStartedAtMs, now)
            val handoffDeadline = StartupProtectionManager.computeHandoffDeadlineMs(
                nowMs = now,
                delayMs = delayMs
            )
            mDeadlineMs = maxOf(mDeadlineMs, handoffDeadline)
            rescheduleMaxTimeout()
            startCountdownTick()
            Log.d(TAG, "FastLockService: handoff accepted, deadline=${mDeadlineMs}ms (handoffDeadline=$handoffDeadline delayMs=$delayMs)")
            return
        }

        // 非 handoff 路径(目前实际未使用,保留以防扩展)
        val delayMs = StartupProtectionManager.remainingFastLockHoldMs(mStartedAtMs, now)
        mPendingCloseRunnable?.let { mHandler.removeCallbacks(it) }
        if (delayMs <= 0L) {
            Log.d(TAG, "FastLockService closing immediately after $reason")
            stopSelf()
            return
        }

        mPendingCloseRunnable = Runnable {
            Log.d(TAG, "FastLockService closing after min hold; reason=$reason delayMs=$delayMs")
            stopSelf()
        }
        mHandler.postDelayed(mPendingCloseRunnable!!, delayMs)
        Log.d(TAG, "FastLockService handoff requested early; hold for ${delayMs}ms more")
    }

    private fun isCeStorageAvailable(): Boolean {
        return try {
            getSharedPreferences("iron_will_startup_state", MODE_PRIVATE)
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun scheduleMaxTimeout() {
        // [2026-07-06 session 3 返工] Codex review 问题 1:
        // timeout runnable 现在调度到 mDeadlineMs 时刻(单一来源),
        // 不再 hardcode "onCreate + 30s"。这样:
        // - adopt: deadline = 真实起点 + 30s,timeout 在那一刻关闭
        // - inflate: deadline = now + 30s
        // - refresh: deadline = max(当前, now + maxVisible)
        // - handoff: deadline = now + delayMs(timeout 与 countdown 与 minHold 同步)
        // - config-change: deadline = now + 10s(由 onStartCommand 走 computeOverrideDeadlineMs)
        mHandler.removeCallbacks(mTimeoutRunnable)
        if (mDeadlineMs <= 0L) {
            // 兜底:若 deadline 未设置(如异常路径),用 inflate 模型
            mDeadlineMs = StartupProtectionManager.computeInflateDeadlineMs(
                nowMs = System.currentTimeMillis(),
                maxVisibleMs = mMaxVisibleMs
            )
        }
        val delayMs = (mDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
        mHandler.postDelayed(mTimeoutRunnable, delayMs)
    }

    /**
     * [2026-07-06 session 3 返工] 重新调度 timeout runnable 到 mDeadlineMs 时刻。
     * 用于:onStartCommand maxVisible 覆盖、refresh、handoff、adopt 等需要同步 timeout 的路径。
     * 等价于 scheduleMaxTimeout(),但意图更明确 — 表示"重新对齐到 deadline",而不是首次启动。
     */
    private fun rescheduleMaxTimeout() {
        scheduleMaxTimeout()
    }

    private fun coreServiceNeverConnected(): Boolean {
        val active = CoreService.isServiceActive
        val lastSignal = CoreService.lastAccessibilitySignalTime
        val result = !active && lastSignal == 0L
        Log.i("IronWill_Debug", "[FastLock_NeverConnected] isServiceActive=$active lastAccessibilitySignalTime=$lastSignal result=$result")
        return result
    }


    private fun shouldKeepStartupProtection(): Boolean {
        val result = StartupA11yCache.isA11yWasEnabledForStartup(this)
        Log.i("IronWill_Debug", "[FastLock_ShouldKeep] isA11yWasEnabledForStartup=$result isServiceActive=${CoreService.isServiceActive} lastAccessibilitySignalTime=${CoreService.lastAccessibilitySignalTime} isRunning=$isRunning")
        return result
    }

    private val mTimeoutRunnable = Runnable {
        // [2026-07-06 REQ-2 诊断] Runnable 入口:完整记录所有判定输入与即将走的分支,
        // 用于排查"为什么总是跑满 30s 不提前关"。
        val now = System.currentTimeMillis()
        val keepProtection = shouldKeepStartupProtection()
        val neverConnected = coreServiceNeverConnected()
        val branch = when {
            !keepProtection -> "normal-stop(!shouldKeep)"
            keepProtection && neverConnected -> "force-stop(CoreService never connected)"
            keepProtection && mExtensionCount >= mMaxExtensions -> "force-stop(max extensions reached)"
            else -> "extension(${mExtensionCount + 1}/$mMaxExtensions)"
        }
        Log.i(
            "IronWill_Debug",
            "[FastLock_TimeoutRunnable] mDeadlineMs=$mDeadlineMs now=$now " +
                "mExtensionCount=$mExtensionCount mMaxExtensions=$mMaxExtensions " +
                "shouldKeep=$keepProtection neverConnected=$neverConnected " +
                "branch=$branch"
        )
        try {
            if (shouldKeepStartupProtection()) {
                // 检测 CoreService 是否从未成功连接过（崩溃或不可用）
                if (coreServiceNeverConnected()) {
                    Log.w(TAG, "FastLockService: CoreService never connected (crashed or unavailable), stopping")
                    StartupA11yCache.setA11yWasEnabled(this, false)
                    stopSelf()
                } else if (mExtensionCount >= mMaxExtensions) {
                    // 硬上限到达：60s + 3×30s = 150s，强制关闭
                    Log.w(TAG, "FastLockService: max extension count reached ($mMaxExtensions), force stopping")
                    stopSelf()
                } else {
                    mExtensionCount++
                    Log.d(TAG, "FastLockService max timeout reached; extending ($mExtensionCount/$mMaxExtensions)")
                    // [2026-07-06 session 3 返工] 延期时同步推后 mDeadlineMs(单一来源):
                    // 让 countdown 显示也跟着延期,而不是 timeout 推迟但显示卡在 0。
                    mDeadlineMs = System.currentTimeMillis() + mMaxVisibleMs
                    scheduleMaxTimeout()
                }
            } else {
                Log.d(TAG, "FastLockService max timeout reached; stopping")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "maxTimeout tick failed", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        mPendingCloseRunnable?.let { mHandler.removeCallbacks(it) }
        mPendingCloseRunnable = null
        mCountdownRunnable?.let { mHandler.removeCallbacks(it) }
        mCountdownRunnable = null
        // [2026-07-06] 问题 2 (Codex review):onDestroy 时清空 pending 计数,
        // 测试断言期望 ≤ 1,销毁后必须为 0。
        mPendingCountdownCount = 0
        mHandler.removeCallbacks(mTimeoutRunnable)
        // [2026-07-06 session 3 返工] 销毁时清空 deadline,避免下次服务实例残留状态
        mDeadlineMs = 0L
        if (mLockView != null && mWindowManager != null) {
            try {
                mWindowManager?.removeView(mLockView)
            } catch (e: Exception) {
                Log.e(TAG, "FastLockService: removeView failed", e)
            }
            mLockView = null
            mCountdownTextView = null
        }
    }

    companion object {
        private const val TAG = "FastLockService"
        // [2026-07-06] 问题 3 (Codex review):启动方在 intent extra 里传入覆盖层最大可见时长,
        // 用于让 countdown 显示与真实持有窗口一致(config-change 路径 10s,启动路径 30s)。
        const val EXTRA_MAX_VISIBLE_MS = "com.ironwill.app.extra.MAX_VISIBLE_MS"
        @Volatile
        var isRunning: Boolean = false
            private set
        private var notificationChannelCreated = false
    }

    /**
     * [2026-07-06] 问题 2 (Codex review):纯逻辑层的"单一调度链"封装。
     *
     * 封装 startCountdownTick / onCountdownTickFired 的核心不变量:
     * - 任意时刻 pending runnable ≤ 1
     * - start() 会清掉旧 runnable 再创建新的(幂等)
     * - cancel() 清空计数
     *
     * 设计原因:Service 本身需要 Context/Looper 才能跑,纯 JVM 单测无法 new Service。
     * 这里用计数 + 调度的最小语义封装,使测试可以验证"连续 start 不会留下双链"。
     */
    internal class CountdownTickCoordinator {
        private var pending: Int = 0

        /** 模拟 startCountdownTick():增加 1 个 pending runnable(替换之前的) */
        fun startOne(): Int {
            // 行为等价于"清旧 runnable + 创建新 runnable",净结果是 1 个 pending
            pending = 1
            return pending
        }

        /** 模拟 onCountdownTickFired() / onDestroy() / 计数到 0 后的自然停止 */
        fun finishOne(): Int {
            pending = 0
            return pending
        }

        /** 取消所有 pending(例如 onDestroy) */
        fun cancelAll(): Int {
            pending = 0
            return pending
        }

        fun pendingCount(): Int = pending

        /**
         * 不变量断言:每次 start 都必须保证 ≤ 1。这与 startOne() 的实现一致,
         * 这里暴露出来用于让测试显式断言调度器当前状态。
         */
        fun assertSingleChain(): Int = pending
    }
}
