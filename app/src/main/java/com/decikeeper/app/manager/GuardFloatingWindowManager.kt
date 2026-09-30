/**
 * =================================================================================
 * [Context Header]
 * * @file: GuardFloatingWindowManager.kt
 * * @role: Domain Skill / System Window Protection
 * * @description: 常驻守护微型小浮窗（自律守护盾）。
 * * @responsibilities:
 * 1. 利用 WindowManager 的 TYPE_APPLICATION_OVERLAY 挂载常驻系统窗口，
 *    使 Android 底层 AMS 将进程评定为 VISIBLE_APP，彻底免疫多任务卡片误划/强杀导致的 force-stop！
 * 2. 贴边自律小盾牌交互：轻触查看守护状态，支持手指随意拖拽与屏幕左右边缘磁吸吸附。
 * 3. 贴边静止自动低透光（透明度 0.45），极简不挡视线。
 * * @constraints:
 * 1. 依赖 SYSTEM_ALERT_WINDOW 权限，未授权时安全降级不崩溃。
 * 2. 必须在主线程操作 WindowManager。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt

object GuardFloatingWindowManager {

    enum class State {
        ACTIVE,
        PENDING,
        DISABLED
    }

    private const val TAG = "GuardFloatingWindow"
    private const val PREFS_NAME = "guard_floating_window_prefs"
    private const val KEY_ENABLED = "floating_guard_enabled"
    private const val KEY_DESIRED_ENABLED = "floating_guard_desired_enabled"
    private const val KEY_PENDING_DISABLE = "floating_guard_pending_disable"
    private const val KEY_LAST_X = "floating_last_x"
    private const val KEY_LAST_Y = "floating_last_y"
    // 与 LockWindowManager 的持久化临时解锁截止时间保持同一数据契约。
    private const val LOCK_WINDOW_STATE_PREFS = "decikeeper_lock_window_state"
    private const val KEY_TEMP_ACCESS_UNTIL = "temp_access_until"
    private const val COUNTDOWN_REFRESH_INTERVAL_MS = 1_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var floatingRootView: View? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isShowing = false
    private var countdownRefreshRunnable: Runnable? = null
    private var refreshFloatingView: (() -> Unit)? = null

    private fun getDesiredEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DESIRED_ENABLED, prefs.getBoolean(KEY_ENABLED, false))
    }

    private fun setDesiredEnabled(
        context: Context,
        enabled: Boolean,
        caller: String = "unknown",
        reason: String = "unspecified"
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldDesired = prefs.getBoolean(KEY_DESIRED_ENABLED, prefs.getBoolean(KEY_ENABLED, false))

        val stackHint = Thread.currentThread().stackTrace
            .drop(2)
            .filterNot { it.className.contains("GuardFloatingWindowManager") && it.methodName == "setDesiredEnabled" }
            .take(4)
            .joinToString(" -> ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

        val runIdTag = ProtectionDiagnostics.currentTestRunId?.let { "runId=$it " } ?: ""
        Log.w(
            TAG,
            "[GUARD_DESIRED_WRITE] ${runIdTag}old=$oldDesired new=$enabled caller=$caller reason=$reason stackHint=$stackHint"
        )

        prefs.edit()
            .putBoolean(KEY_DESIRED_ENABLED, enabled)
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun getProtectionState(context: Context): State {
        val desired = getDesiredEnabled(context)
        if (!desired) return State.DISABLED

        val effectiveTime = ConfigPendingManager.getEffectiveTime(context, KEY_PENDING_DISABLE)
        if (effectiveTime == 0L) return State.ACTIVE

        val now = System.currentTimeMillis()
        if (now < effectiveTime) {
            return State.PENDING
        }

        val runIdTag = ProtectionDiagnostics.currentTestRunId?.let { "runId=$it " } ?: ""
        Log.w(TAG, "[GUARD_EXPIRED] ${runIdTag}Pending expired, auto-disabling. effectiveTime=$effectiveTime now=$now")
        setDesiredEnabled(context, false, caller = "getProtectionState", reason = "pending_expired")
        ConfigPendingManager.clearPending(context, KEY_PENDING_DISABLE)
        hide(context, caller = "getProtectionState", reason = "pending_expired")
        return State.DISABLED
    }

    fun isEnforcing(context: Context): Boolean {
        val state = getProtectionState(context)
        return state == State.ACTIVE || state == State.PENDING
    }

    /**
     * 判断当前 Switch 视觉状态：仅 ACTIVE 时显示 checked。
     */
    fun isFloatingGuardEnabled(context: Context): Boolean {
        return getProtectionState(context) == State.ACTIVE
    }

    /**
     * 设置常驻守护小浮窗开启/关闭状态。
     * 遵守产品原则「严立宽延」：
     * - 开启：立即生效 (ACTIVE) 并挂载小浮窗；
     * - 关闭（正常保护期）：提交 strictNextDay 次日生效延迟 (PENDING)，等待期内浮窗继续挂载；
     * - 关闭（适应期）：立即关闭 (DISABLED) 并卸载浮窗。
     */
    fun setFloatingGuardEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        val runIdTag = ProtectionDiagnostics.currentTestRunId?.let { "runId=$it " } ?: ""
        Log.i(TAG, "[GUARD_SET_ENABLED] ${runIdTag}enabled=$enabled currentDesired=${getDesiredEnabled(appContext)}")
        if (enabled) {
            setDesiredEnabled(appContext, true, caller = "setFloatingGuardEnabled", reason = "user_enable")
            ConfigPendingManager.clearPending(appContext, KEY_PENDING_DISABLE)
            show(appContext, caller = "setFloatingGuardEnabled", reason = "user_enable")
            return
        }

        val baselineActive = TimeRuleManager.getBaselineFloatingGuardState(appContext)
        if (TimeRuleManager.isInAdaptationPeriod(appContext) || !baselineActive) {
            setDesiredEnabled(appContext, false, caller = "setFloatingGuardEnabled", reason = "adaptation_or_baseline_restore")
            ConfigPendingManager.clearPending(appContext, KEY_PENDING_DISABLE)
            hide(appContext, caller = "setFloatingGuardEnabled", reason = "adaptation_or_baseline_restore")
            return
        }

        ConfigPendingManager.submitChange(
            context = appContext,
            key = KEY_PENDING_DISABLE,
            value = "false",
            strictNextDay = true
        )
        // PENDING 期间浮窗继续挂载防护
        show(appContext, caller = "setFloatingGuardEnabled", reason = "submit_pending_disable")
    }

    internal fun disableImmediatelyForAccessibilityUnavailable(
        context: Context,
        caller: String = "AccessibilityDependencyManager"
    ) {
        val appContext = context.applicationContext
        val oldDesired = getDesiredEnabled(appContext)
        val oldState = getProtectionState(appContext)
        val oldPending = ConfigPendingManager.getEffectiveTime(appContext, KEY_PENDING_DISABLE)

        setDesiredEnabled(
            appContext,
            false,
            caller = caller,
            reason = "disableImmediatelyForAccessibilityUnavailable"
        )
        ConfigPendingManager.clearPending(appContext, KEY_PENDING_DISABLE)
        hide(appContext, caller = caller, reason = "accessibility_unavailable")

        val newDesired = getDesiredEnabled(appContext)
        val newState = getProtectionState(appContext)

        val runIdTag = ProtectionDiagnostics.currentTestRunId?.let { "runId=$it " } ?: ""
        Log.w(
            "ProtectionDiagnostics",
            "[PROTECTION_FORCE_DISABLE] ${runIdTag}component=guard_floating_window caller=$caller " +
                    "oldEnabled=$oldDesired oldState=$oldState oldPending=$oldPending " +
                    "newEnabled=$newDesired newState=$newState"
        )
    }

    /**
     * 检查是否已具备悬浮窗权限（SYSTEM_ALERT_WINDOW）。
     */
    fun canDrawOverlays(context: Context): Boolean {
        return PermissionManager.hasOverlayPermission(context)
    }

    /**
     * 跳转至系统悬浮窗权限授予设置页。
     */
    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            RecentTasksHideManager.markNavigating()
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open overlay permission settings", e)
                RecentTasksHideManager.clearNavigating()
            }
        }
    }

    /**
     * 在屏幕上展示常驻守护小浮窗。
     */
    fun show(context: Context, caller: String = "unknown", reason: String = "unspecified") {
        val appContext = context.applicationContext
        val runIdTag = ProtectionDiagnostics.currentTestRunId?.let { "runId=$it " } ?: ""
        val enforcing = isEnforcing(appContext)
        val canDraw = canDrawOverlays(appContext)
        Log.i(TAG, "[GUARD_SHOW] ${runIdTag}caller=$caller reason=$reason isEnforcing=$enforcing canDraw=$canDraw isShowing=$isShowing")
        if (!enforcing) {
            hide(appContext, caller = "show_not_enforcing", reason = reason)
            return
        }
        if (!canDraw) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted, skipping show")
            return
        }

        mainHandler.post {
            try {
                if (isShowing && floatingRootView != null) return@post

                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                windowManager = wm

                val dm = appContext.resources.displayMetrics
                val screenWidth = dm.widthPixels
                val screenHeight = dm.heightPixels

                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val initialX = prefs.getInt(KEY_LAST_X, 0)
                val initialY = prefs.getInt(KEY_LAST_Y, screenHeight / 3)

                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = initialX
                    y = initialY
                }
                layoutParams = params

                val view = createFloatingPillView(appContext, params, screenWidth)
                floatingRootView = view

                wm.addView(view, params)
                isShowing = true
                refreshFloatingView?.invoke()
                countdownRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
                val refresh = object : Runnable {
                    override fun run() {
                        if (floatingRootView == null) return
                        refreshFloatingView?.invoke()
                        mainHandler.postDelayed(this, COUNTDOWN_REFRESH_INTERVAL_MS)
                    }
                }
                countdownRefreshRunnable = refresh
                mainHandler.postDelayed(refresh, COUNTDOWN_REFRESH_INTERVAL_MS)
                Log.i(TAG, "Guard floating window attached successfully! (Process now prioritized as VISIBLE_APP)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add floating guard window", e)
            }
        }
    }

    /**
     * 隐藏并移除常驻守护小浮窗。
     */
    fun hide(context: Context, caller: String = "unknown", reason: String = "unspecified") {
        val appContext = context.applicationContext
        val runIdTag = ProtectionDiagnostics.currentTestRunId?.let { "runId=$it " } ?: ""
        Log.i(TAG, "[GUARD_HIDE] ${runIdTag}caller=$caller reason=$reason isShowing=$isShowing")
        mainHandler.post {
            countdownRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
            countdownRefreshRunnable = null
            refreshFloatingView = null
            try {
                floatingRootView?.let { v ->
                    windowManager?.removeView(v)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove floating guard window", e)
            } finally {
                floatingRootView = null
                isShowing = false
            }
        }
    }

    /**
     * 构建质感贴边自律小盾牌 View。
     */
    private fun createFloatingPillView(
        context: Context,
        params: WindowManager.LayoutParams,
        screenWidth: Int
    ): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dp8 = dpToPx(context, 8f)
        val dp6 = dpToPx(context, 6f)
        val dp4 = dpToPx(context, 4f)

        // 无倒计时时保留原盾牌；有倒计时时完全替换成纯数字。
        val pillContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp6, dp6, dp6, dp6)

            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6182230")) // 质感暗夜深海色
                cornerRadius = dpToPx(context, 18f).toFloat()
                setStroke(dpToPx(context, 1f), Color.parseColor("#4D5E81AC")) // 优雅边框
            }
            alpha = 0.55f // 默认贴边静止半透明
        }

        val tvShield = TextView(context).apply {
            text = "🛡️"
            textSize = 16f
            gravity = Gravity.CENTER
        }
        pillContainer.addView(tvShield)

        val tvCountdown = TextView(context).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor("#ECEFF4".toColorInt())
            gravity = Gravity.CENTER
            includeFontPadding = false
            visibility = View.GONE
        }
        pillContainer.addView(tvCountdown)

        val detailsPanel = TextView(context).apply {
            textSize = 12.5f
            setTextColor("#ECEFF4".toColorInt())
            setPadding(dp8, dp6, dp8, dp6)
            setLineSpacing(0f, 1.15f)
            includeFontPadding = false
            background = GradientDrawable().apply {
                setColor("#F0182230".toColorInt())
                cornerRadius = dpToPx(context, 12f).toFloat()
                setStroke(dpToPx(context, 1f), "#4D5E81AC".toColorInt())
            }
            visibility = View.GONE
        }

        var expanded = false
        var showingCountdown = false

        fun activeItems(nowMs: Long): List<FloatingExitCountdown> {
            val privilegeActive = CreditManager.isWeeklyPrivilegeSessionActive(context)
            val freeTimeEnd = CreditManager.getFreeTimeEnd(context)
            val temporaryUnlockEnd = context
                .getSharedPreferences(LOCK_WINDOW_STATE_PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_TEMP_ACCESS_UNTIL, 0L)
            return GuardFloatingCountdown.expandedItems(
                nowMs = nowMs,
                privilegeEndMs = if (privilegeActive) freeTimeEnd else 0L,
                temporaryUnlockEndMs = temporaryUnlockEnd,
                restEndMs = if (!privilegeActive) freeTimeEnd else 0L
            )
        }

        fun rebuild(keepRightSide: Boolean = params.x + root.width / 2 >= screenWidth / 2) {
            root.removeAllViews()
            if (expanded && showingCountdown) {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (keepRightSide) {
                        marginEnd = dp4
                    } else {
                        marginStart = dp4
                    }
                }
                detailsPanel.layoutParams = lp

                if (keepRightSide) {
                    root.addView(detailsPanel)
                    root.addView(pillContainer)
                } else {
                    root.addView(pillContainer)
                    root.addView(detailsPanel)
                }
            } else {
                root.addView(pillContainer)
            }
            root.post {
                val maxX = (screenWidth - root.width).coerceAtLeast(0)
                params.x = if (keepRightSide) maxX else params.x.coerceIn(0, maxX)
                try {
                    windowManager?.updateViewLayout(root, params)
                } catch (_: Exception) {
                    // Window may be between attach/detach on an OEM thread.
                }
            }
        }

        fun render(nowMs: Long = System.currentTimeMillis()) {
            val items = activeItems(nowMs)
            val hasCountdown = items.isNotEmpty()
            val collapsedText = items.firstOrNull()?.let {
                if (it.remainingSeconds > 60 * 60L) ">1h" else it.text
            }
            tvShield.visibility = if (hasCountdown) View.GONE else View.VISIBLE
            tvCountdown.visibility = if (hasCountdown) View.VISIBLE else View.GONE
            tvCountdown.text = collapsedText.orEmpty()
            detailsPanel.text = items.joinToString("\n") { "${it.label}    ${it.text}" }

            if (hasCountdown != showingCountdown || (!hasCountdown && expanded)) {
                val keepRightSide = params.x + root.width / 2 >= screenWidth / 2
                showingCountdown = hasCountdown
                if (!hasCountdown) expanded = false
                rebuild(keepRightSide)
            } else {
                detailsPanel.visibility = if (expanded) View.VISIBLE else View.GONE
            }
        }

        refreshFloatingView = { render() }
        render()

        // 仅降低透明度，不自动收起展开面板。
        var fadeRunnable: Runnable? = null
        fun scheduleFadeOut() {
            fadeRunnable?.let { mainHandler.removeCallbacks(it) }
            val r = Runnable {
                pillContainer.animate().alpha(0.40f).setDuration(400).start()
            }
            fadeRunnable = r
            mainHandler.postDelayed(r, 2500L)
        }

        scheduleFadeOut()

        // 触摸拖拽与左右贴边
        root.setOnTouchListener(object : View.OnTouchListener {
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var initialX = 0
            private var initialY = 0
            private var isMoved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        fadeRunnable?.let { mainHandler.removeCallbacks(it) }
                        pillContainer.alpha = 0.95f
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoved = true
                        }
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        try {
                            windowManager?.updateViewLayout(root, params)
                        } catch (e: Exception) {
                            // Ignored during drag
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoved) {
                            v.performClick()
                            val now = System.currentTimeMillis()
                            val hasCountdown = activeItems(now).isNotEmpty()
                            if (hasCountdown) {
                                val keepRightSide = params.x + root.width / 2 >= screenWidth / 2
                                expanded = !expanded
                                showingCountdown = true
                                detailsPanel.visibility = if (expanded) View.VISIBLE else View.GONE
                                pillContainer.alpha = 0.95f
                                rebuild(keepRightSide)
                                if (!expanded) scheduleFadeOut()
                            }
                        } else {
                            // 拖拽松手：自动吸附至左侧或右侧
                            val currentX = params.x
                            val targetX = if (currentX + root.width / 2 < screenWidth / 2) {
                                0 // 吸附左侧
                            } else {
                                screenWidth - root.width // 吸附右侧
                            }
                            params.x = targetX
                            try {
                                windowManager?.updateViewLayout(root, params)
                            } catch (e: Exception) {
                                // Ignored
                            }

                            // 保存最后停留位置
                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putInt(KEY_LAST_X, params.x)
                                .putInt(KEY_LAST_Y, params.y)
                                .apply()

                            if (!expanded) scheduleFadeOut()
                        }
                        return true
                    }
                }
                return false
            }
        })

        return root
    }

    private fun dpToPx(context: Context, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }
}
