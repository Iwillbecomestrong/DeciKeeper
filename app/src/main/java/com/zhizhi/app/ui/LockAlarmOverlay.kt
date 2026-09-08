/**
 * =================================================================================
 * [Context Header]
 * * @file: LockAlarmOverlay.kt
 * * @role: UI
 * * @description: 闹钟挑战覆盖层的创建、题目滚动、复习回顾与关闭控制。
 * * @responsibilities:
 * 1. 管理闹钟挑战覆盖层的显示与状态更新。
 * 2. 处理数学/诗词/单词/选词填空/扫码挑战与两阶段解锁。
 * 3. 构建会话级复习回顾卡片。
 *
 * - 上游: LockWindowManager（委托）、AlarmActivity、AlarmWindowManager
 * - 下游: WindowManager、AlarmScheduler、AlarmChallengeManager、AlarmVibrationService
 * * @constraints (必读):
 * 1. 行为零变化：自 LockWindowManager.kt 拆分而来，保留原日志/文案/判断顺序。
 * 2. 共享状态（mAlarmView / session* / mContext / mCoreService / mWindowManager）
 *    仍由 LockWindowManager 持有，通过 internal getter 访问。
 * =================================================================================
 */
package com.zhizhi.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.zhizhi.app.R
import com.zhizhi.app.data.AndroidLogcatNormalEventSink
import com.zhizhi.app.data.DefaultLogDebugConfig
import com.zhizhi.app.data.DefaultNormalEventRenderer
import com.zhizhi.app.data.LogExporter
import com.zhizhi.app.data.NormalEventLogger
import com.zhizhi.app.data.SystemEventClock
import com.zhizhi.app.manager.AlarmChallengeManager
import com.zhizhi.app.manager.AlarmProtectionEventBridge
import com.zhizhi.app.manager.AlarmScheduler
import com.zhizhi.app.manager.statistics.AlarmWakeStatsRecorder
import com.zhizhi.app.service.AlarmVibrationService
import com.zhizhi.app.service.CoreService
import java.util.concurrent.atomic.AtomicBoolean













@SuppressLint("StaticFieldLeak")
internal object LockAlarmOverlayController {

    private const val TAG = "LockWindowManager"
    internal fun isActiveNow(): Boolean = isAlarmOverlayActive.get()
    internal fun isAttachedNow(): Boolean = isAlarmOverlayAttached
    private val alarmProtectionNormalEventLogger by lazy {
        NormalEventLogger(
            renderer = DefaultNormalEventRenderer,
            sinks = listOf(LogExporter.normalEventSink(), AndroidLogcatNormalEventSink())
        )
    }
    private val alarmProtectionEventBridge by lazy {
        AlarmProtectionEventBridge(
            clock = SystemEventClock,
            emit = alarmProtectionNormalEventLogger::emit,
            debugConfig = DefaultLogDebugConfig
        )
    }

    @Volatile
    private var pendingTestConfig: LockWindowManager.TestAlarmConfig? = null

    fun setTestAlarmConfig(config: LockWindowManager.TestAlarmConfig) {
        pendingTestConfig = config
    }

    private fun consumeTestConfig(): LockWindowManager.TestAlarmConfig? {
        val c = pendingTestConfig
        pendingTestConfig = null
        return c
    }

    private var mAlarmActiveWindowManager: WindowManager? = null
    private var mAlarmController: LockWindowManager.AlarmOverlayController? = null
    private val isAlarmOverlayActive = AtomicBoolean(false)
    private var alarmId: Int = -1
    private var alarmSnoozeMinutes: Int = 10
    private var alarmRemainingSnoozeCount = 0
    private var isAlarmOverlayAttached = false

    @Volatile
    private var isAlarmOverlayPassthrough = false
    @Volatile
    private var isAlarmScanInProgress = false

    private fun isAlarmViewHostedBy(activity: android.app.Activity): Boolean {
        val view = LockWindowManager.getAlarmView() ?: return false
        val decor = activity.window?.decorView ?: return false
        var parent: Any? = view
        while (parent != null) {
            if (parent === decor) return true
            parent = (parent as? View)?.parent
        }
        return false
    }

    /**
     * [DO NOT DELETE] 防残留机制（关联 Issue_Tracker 05-13）
     * 当 isAlarmOverlayActive 已为 true 时，不能直接 return 跳过初始化，
     * 否则会导致：1) 锁屏解锁后覆盖层不显示 2) 后续测试闹钟卡死。
     * 必须清除旧状态后重新创建。
     */
    fun showAlarmOverlay(
        context: Context,
        alarmId: Int,
        snoozeMinutes: Int,
        controller: LockWindowManager.AlarmOverlayController,
        useInline: Boolean = false
    ) {
        LockWindowManager.init(context)
        AlarmChallengeManager.init(context)

        // [DIAG] 记录进入 showAlarmOverlay 时的完整状态
        val layoutParamsType = LockWindowManager.getAlarmView()?.layoutParams?.let { it.javaClass.simpleName } ?: "null"
        Log.i(TAG, "showAlarmOverlay: ENTER alarmId=$alarmId, useInline=$useInline, contextType=${context.javaClass.simpleName}, isAlarmOverlayActive=${isAlarmOverlayActive.get()}, isAlarmOverlayAttached=$isAlarmOverlayAttached, mAlarmView=${LockWindowManager.getAlarmView() != null}, mAlarmView.parent=${LockWindowManager.getAlarmView()?.parent != null}, layoutParamsType=$layoutParamsType, isCurrentOverlayInline=${LockWindowManager.isCurrentOverlayInline()}")
        LogExporter.logEvent("AlarmOverlay", "ENTER id=$alarmId inline=$useInline ctx=${context.javaClass.simpleName} active=${isAlarmOverlayActive.get()} attached=$isAlarmOverlayAttached view=${LockWindowManager.getAlarmView() != null} parent=${LockWindowManager.getAlarmView()?.parent != null} lp=$layoutParamsType currentInline=${LockWindowManager.isCurrentOverlayInline()}")

        // [防御性代码] 如果覆盖层已激活但视图已分离或被系统移除（如Vivo解锁过渡），
        // 必须先清理旧状态再重新创建，否则用户将看不到任何挑战界面
        if (isAlarmOverlayActive.get()) {
            // 如果视图还存在且已附加，说明是正常激活状态 → 更新控制器即可
            if (LockWindowManager.getAlarmView() != null && isAlarmOverlayAttached && LockWindowManager.getAlarmView()?.parent != null) {
                val requestedInline = useInline && context is android.app.Activity && !context.isFinishing
                val currentInline = LockWindowManager.isCurrentOverlayInline()
                val hostedByThisActivity = currentInline && context is android.app.Activity && isAlarmViewHostedBy(context)
                val canReuseAttachedView = if (currentInline) {
                    requestedInline && hostedByThisActivity
                } else {
                    !requestedInline
                }
                if (canReuseAttachedView) {
                    // 更新AlarmController引用（可能来自新Activity）
                    mAlarmController = controller
                    // [Fix: 复用透明] 复用已挂载覆盖层时，确保恢复到可见状态
                    // 前一次扫码的 setAlarmOverlayPassthrough(true) 可能遗留 alpha=0.0 +
                    // NOT_FOCUSABLE + NOT_TOUCHABLE，导致用户看到黑屏（覆盖层完全透明）
                    if (!LockWindowManager.isCurrentOverlayInline() && LockWindowManager.getAlarmView()?.layoutParams is WindowManager.LayoutParams) {
                        val lp = LockWindowManager.getAlarmView()?.layoutParams as WindowManager.LayoutParams
                        if (lp.alpha < 1.0f || (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
                            lp.alpha = 1.0f
                            lp.flags = lp.flags and (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv())
                            mAlarmActiveWindowManager?.updateViewLayout(LockWindowManager.getAlarmView(), lp)
                            isAlarmOverlayPassthrough = false
                            isAlarmScanInProgress = false
                            Log.i(TAG, "showAlarmOverlay: REUSE_ATTACHED restored alpha/focus, oldAlpha=${lp.alpha}")
                            LogExporter.logEvent("AlarmOverlay", "REUSE_RESTORED_VISIBILITY id=$alarmId alpha=${lp.alpha}")
                        }
                    }
                    Log.d(TAG, "showAlarmOverlay: overlay already active and attached, updating controller")
                    LogExporter.logEvent("AlarmOverlay", "REUSE_ATTACHED id=$alarmId currentInline=$currentInline requestedInline=$requestedInline hostedByThisActivity=$hostedByThisActivity")
                    return
                }
                Log.w(TAG, "showAlarmOverlay: attached overlay mode/host mismatch, force-resetting (currentInline=$currentInline, requestedInline=$requestedInline, hostedByThisActivity=$hostedByThisActivity)")
                LogExporter.logEvent("AlarmOverlay", "RESET_MISMATCH id=$alarmId currentInline=$currentInline requestedInline=$requestedInline hostedByThisActivity=$hostedByThisActivity")
                forceResetAlarmOverlay()
            } else {
                // 视图不存在或已分离 → 先完全清理旧状态再重新初始化
                Log.w(TAG, "showAlarmOverlay: stale overlay detected (active=$isAlarmOverlayActive, attached=$isAlarmOverlayAttached, view=${LockWindowManager.getAlarmView() != null}), force-resetting")
                LogExporter.logEvent("AlarmOverlay", "RESET_STALE id=$alarmId active=${isAlarmOverlayActive.get()} attached=$isAlarmOverlayAttached view=${LockWindowManager.getAlarmView() != null} parent=${LockWindowManager.getAlarmView()?.parent != null}")
                forceResetAlarmOverlay()
            }
        }

        this.alarmId = alarmId
        this.alarmSnoozeMinutes = snoozeMinutes
        this.alarmRemainingSnoozeCount = if (alarmId > 0) AlarmScheduler.getRemainingSnoozeCount(context, alarmId) else 0
        mAlarmController = controller

        val entry = AlarmScheduler.findAlarm(context, alarmId)
        // 如果是测试闹钟 (alarmId <= 0)，读取测试配置，不强制启用全部选项
        val isTest = alarmId <= 0
        val testCfg = if (isTest) consumeTestConfig() else null

        // [Fix: 禁止默认配置回退] 正式闹钟触发时，entry 为 null 或 unlockMode 解析失败表示记录已被删除或配置损坏，
        // 此时必须拒绝响铃而不是回退到默认 SINGLE 模式。
        val unlockMode = if (isTest) {
            testCfg?.unlockMode ?: AlarmScheduler.UnlockMode.SINGLE
        } else {
            if (entry == null) {
                Log.w(TAG, "showAlarmOverlay: alarm entry not found, rejecting with NOT_FOUND")
                LogExporter.logEvent("AlarmOverlay", "SHOW_REJECTED_NOT_FOUND alarmId=$alarmId")
                return
            }
            entry.unlockMode
        }
        isTwoStageUnlock = unlockMode == AlarmScheduler.UnlockMode.TWO_STAGE
        isAlarmFirstStagePassed = AlarmScheduler.isFirstStagePassed(context)
        alarmStageCooldownSeconds = if (isTest) (testCfg?.stageCooldownSeconds ?: 0) else (entry?.stageCooldownSeconds ?: AlarmScheduler.getDefaultStageCooldownSeconds(context))

        // Read per-stage challenge assignment first, then derive effective booleans
        // from the union of both stages so types assigned only to stage 2 are counted.
        alarmStage1Types = if (isTest) (testCfg?.stage1Types ?: setOf("qr", "math")) else (entry?.stage1Types ?: setOf("qr", "math"))
        alarmStage2Types = if (isTest) (testCfg?.stage2Types ?: setOf("math", "poem", "word")) else (entry?.stage2Types ?: setOf("math", "poem", "word"))
        val allTypes = alarmStage1Types + alarmStage2Types

        val useQr = if (isTest) (testCfg?.useQr ?: true) else (entry?.useQr ?: true)
        val effectiveUseQr = "qr" in allTypes || useQr
        val effectiveUseMath = "math" in allTypes
        val effectiveUsePoem = "poem" in allTypes
        val effectiveUseWord = "word" in allTypes
        val effectiveUseCloze = "cloze" in allTypes
        val effectiveUseCustom = "custom" in allTypes

        targetMathCount = if (isTest) (if (effectiveUseMath) (testCfg?.mathCount ?: 1) else 0) else (if (effectiveUseMath) (entry?.mathCount ?: 2) else 0)
        targetPoemCount = if (isTest) (if (effectiveUsePoem) (testCfg?.poemCount ?: 1) else 0) else (if (effectiveUsePoem) (entry?.poemCount ?: 3) else 0)
        targetWordCount = if (isTest) (if (effectiveUseWord) (testCfg?.wordCount ?: 1) else 0) else (if (effectiveUseWord) (entry?.wordCount ?: 10) else 0)
        targetClozeCount = if (isTest) (if (effectiveUseCloze) (testCfg?.clozeCount ?: 3) else 0) else (if (effectiveUseCloze) (entry?.clozeCount ?: 5) else 0)
        targetCustomCount = if (isTest) (if (effectiveUseCustom) (testCfg?.customCount ?: 3) else 0) else (if (effectiveUseCustom) (entry?.customCount ?: 3) else 0)
        alarmStage1CustomBanks = if (isTest) (testCfg?.stage1CustomBanks ?: emptyMap()) else (entry?.stage1CustomBanks ?: emptyMap())
        alarmStage2CustomBanks = if (isTest) (testCfg?.stage2CustomBanks ?: emptyMap()) else (entry?.stage2CustomBanks ?: emptyMap())
        rebuildCustomQueue()

        // Store vibration mode for AlarmVibrationService to read
        testVibrationMode = if (isTest) (testCfg?.vibrationMode ?: AlarmScheduler.VibrationMode.PROGRESSIVE) else null
        testVibrationIntensity = if (isTest) (testCfg?.vibrationIntensity ?: 7) else null
        testAlarmMode = if (isTest) (testCfg?.alarmMode ?: AlarmScheduler.AlarmMode.VIBRATE_AND_RING) else null
        testRingtoneUri = if (isTest) (testCfg?.ringtoneUri) else null
        testRingVolume = if (isTest) (testCfg?.ringVolume) else null
        testProgressiveMinIntensity = if (isTest) (testCfg?.progressiveMinIntensity ?: 1) else null
        testProgressiveMaxIntensity = if (isTest) (testCfg?.progressiveMaxIntensity ?: 10) else null
        testProgressiveMaxRingVolume = if (isTest) (testCfg?.progressiveMaxRingVolume ?: 10) else null
        testAutoEscalate = if (isTest) (testCfg?.autoEscalate ?: false) else null

        // 如果啥都没选，强制用扫码
        val finalUseQr = if (!effectiveUseQr && targetMathCount == 0 && targetPoemCount == 0 && targetWordCount == 0 && targetClozeCount == 0 && targetCustomCount == 0) true else effectiveUseQr
        isQrEnabled = finalUseQr

        Log.i(TAG, "showAlarmOverlay: isTest=$isTest, s1=$alarmStage1Types, s2=$alarmStage2Types, math=$targetMathCount, poem=$targetPoemCount, word=$targetWordCount, cloze=$targetClozeCount, custom=$targetCustomCount, qr=$isQrEnabled, twoStage=$isTwoStageUnlock")

        val ctx = LockWindowManager.getAlarmContext() ?: return
        val themedContext = ContextThemeWrapper(ctx, ThemeManager.themeRes(ctx))
        try {
            if (LockWindowManager.getAlarmView() == null) {
                LockWindowManager.setAlarmView(android.view.LayoutInflater.from(themedContext).inflate(R.layout.window_alarm_overlay, null))
            }
            if (mAlarmActiveWindowManager == null) {
                // Use CoreService's WindowManager when A11y is active — TYPE_ACCESSIBILITY_OVERLAY
                // requires the accessibility service's window token, not the app's default one.
                val a11yWm = if (CoreService.instance != null && CoreService.isServiceActive) {
                    CoreService.instance!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                } else null
                mAlarmActiveWindowManager = a11yWm ?: ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }

            resetAlarmQuestions()
            LockWindowManager.getSessionPoems().clear()
            LockWindowManager.getSessionWords().clear()
            LockWindowManager.getSessionClozes().clear()
            LockWindowManager.getSessionCustomQuestions().clear()
            currentPoem = null
            currentWord = null
            currentCloze = null
            currentCustomQuestion = null
            initAlarmUI()
            syncChallengeUiByStage()

            // TYPE_ACCESSIBILITY_OVERLAY requires explicit focus to receive touch events
            LockWindowManager.getAlarmView()?.isFocusable = true
            LockWindowManager.getAlarmView()?.isFocusableInTouchMode = true
            LockWindowManager.getAlarmView()?.requestFocus()

            // [锁屏适配] 当调用方（如 AlarmActivity）标记 useInline=true 时，
            // 将挑战界面直接内嵌到 Activity 中（setContentView），
            // 而非添加到 WindowManager。因为 WindowManager 覆盖层的层级低于系统锁屏，
            // 而 Activity 通过 setShowWhenLocked(true) 可以盖在系统锁屏之上。
            // 这样用户在不解锁的情况下也能看到挑战界面并使用扫码。
            // ★ useInline 由调用方根据场景决策（锁屏 vs 非锁屏），
            //   确保测试/正常解锁场景仍用 addView，避免 setContentView 卡死。
            Log.i(TAG, "showAlarmOverlay: inlineBranchDecision useInline=$useInline, isActivity=${context is android.app.Activity}, isFinishing=${(context as? android.app.Activity)?.isFinishing}")
            if (useInline && context is android.app.Activity && !context.isFinishing) {
                context.setContentView(LockWindowManager.getAlarmView())
                isAlarmOverlayActive.set(true)
                isAlarmOverlayAttached = true
                Log.i(TAG, "showAlarmOverlay: inline mode (by caller request), isCurrentOverlayInline=${LockWindowManager.isCurrentOverlayInline()}")
                LogExporter.logEvent("AlarmOverlay", "SHOW_INLINE id=$alarmId ctx=${context.javaClass.simpleName}")
            } else {
                val params = getAlarmLayoutParams()
                mAlarmActiveWindowManager?.addView(LockWindowManager.getAlarmView(), params)
                isAlarmOverlayActive.set(true)
                isAlarmOverlayAttached = true
                Log.i(TAG, "showAlarmOverlay: addView mode, layoutParamsType=${LockWindowManager.getAlarmView()?.layoutParams?.javaClass?.simpleName}")
                LogExporter.logEvent("AlarmOverlay", "SHOW_WINDOW id=$alarmId lp=${LockWindowManager.getAlarmView()?.layoutParams?.javaClass?.simpleName}")
            }

            Log.i(TAG, "showAlarmOverlay: starting vibration for alarmId=$alarmId")
            // 关键：在 startForegroundService 之前同步写入标志位
            // 因为 startForegroundService 是异步的，一键强制重启可能在 Service 的 onStartCommand 执行前就杀死进程
            // 所以必须在此处同步 commit() 到磁盘，确保重启后能检测到
            AlarmScheduler.setAlarmActive(ctx, alarmId)
            LogExporter.logEvent("AlarmRecovery", "setAlarmActive(id=$alarmId) before service start")
            AlarmVibrationService.start(ctx, alarmId)
            startAlarmIntensityUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "showAlarmOverlay failed", e)
            // [防御性] 悬浮窗权限缺失或 TYPE_ACCESSIBILITY_OVERLAY 不可用时，
            // 回退为 Activity 内嵌 UI
            // TYPE_APPLICATION_OVERLAY 需要 SYSTEM_ALERT_WINDOW 权限，新安装时可能未授予
            // 此时 setContentView 将闹钟 UI 直接放入 Activity 窗口，避免空白卡死
            // [Fix] context 参数是 AlarmActivity，而 ctx (=mContext) 是 Application Context
            //       ctx is Activity 永远为 false，必须用 context 判断
            var handledInline = false
            // [锁屏适配] 仅在调用方要求 useInline 时回退才生效
            if (useInline && LockWindowManager.getAlarmView()?.parent == null && context is android.app.Activity && !context.isFinishing) {
                try {
                    context.setContentView(LockWindowManager.getAlarmView()!!)
                    isAlarmOverlayActive.set(true)
                    isAlarmOverlayAttached = true
                    handledInline = true
                    Log.i(TAG, "showAlarmOverlay: overlay permission denied, showing inline in Activity")
                    // 恢复原 catch 前应执行的步骤
                    AlarmScheduler.setAlarmActive(context, alarmId)
                    LogExporter.logEvent("AlarmRecovery", "setAlarmActive(id=$alarmId) inline fallback")
                    AlarmVibrationService.start(context, alarmId)
                    startAlarmIntensityUpdate()
                } catch (e2: Exception) {
                    Log.e(TAG, "showAlarmOverlay inline fallback also failed", e2)
                }
            }
            if (!handledInline) {
                throw e
            }
        }
    }

    /**
     * [DO NOT DELETE] 强制重置闹钟覆盖层状态（关联 Issue_Tracker 05-13）
     * 当有残留的覆盖层状态时调用，清理 WindowManager 视图和标志位。
     * 与 dismissAlarmOverlay 不同，不触发 Toast/回调/停止震动。
     */
    private fun forceResetAlarmOverlay() {
        val viewType = LockWindowManager.getAlarmView()?.layoutParams?.let { it.javaClass.simpleName } ?: "null"
        val currentInline = LockWindowManager.isCurrentOverlayInline()
        Log.w(TAG, "forceResetAlarmOverlay: mAlarmView=${LockWindowManager.getAlarmView() != null}, parent=${LockWindowManager.getAlarmView()?.parent != null}, wm=$mAlarmActiveWindowManager, layoutParamsType=$viewType, isCurrentOverlayInline=$currentInline")
        LogExporter.logEvent("AlarmOverlay", "FORCE_RESET_BEGIN view=${LockWindowManager.getAlarmView() != null} parent=${LockWindowManager.getAlarmView()?.parent != null} lp=$viewType inline=$currentInline")
        try {
            if (!currentInline && LockWindowManager.getAlarmView() != null && mAlarmActiveWindowManager != null && LockWindowManager.getAlarmView()?.parent != null) {
                // [Fix: a11y overlay crash] removeView 前清空焦点
                LockWindowManager.getAlarmView()?.clearFocus()
                mAlarmActiveWindowManager?.removeView(LockWindowManager.getAlarmView())
            } else if (currentInline && LockWindowManager.getAlarmView()?.parent != null) {
                // [Fix: a11y overlay crash] removeView 前清空焦点
                LockWindowManager.getAlarmView()?.clearFocus()
                (LockWindowManager.getAlarmView()?.parent as? android.view.ViewManager)?.removeView(LockWindowManager.getAlarmView())
            }
        } catch (_: Exception) {
        }
        mAlarmActiveWindowManager = null
        LockWindowManager.setAlarmView(null)
        isAlarmOverlayAttached = false
        isAlarmOverlayActive.set(false)
        mAlarmController = null
        Log.i(TAG, "forceResetAlarmOverlay: stale overlay state cleared")
        LogExporter.logEvent("AlarmOverlay", "FORCE_RESET_DONE")
    }

    private var targetMathCount = 0
    private var targetPoemCount = 0
    private var targetWordCount = 0
    private var targetClozeCount = 0
    private var targetCustomCount = 0
    private var alarmStage1CustomBanks: Map<String, Int> = emptyMap()
    private var alarmStage2CustomBanks: Map<String, Int> = emptyMap()
    private val customBankQueue = mutableListOf<String?>()

    private fun rebuildCustomQueue() {
        customBankQueue.clear()
        val bankMap = if (!isAlarmFirstStagePassed) alarmStage1CustomBanks else alarmStage2CustomBanks
        if (bankMap.isNotEmpty()) {
            for ((bId, count) in bankMap) {
                repeat(count) { customBankQueue.add(bId) }
            }
            targetCustomCount = customBankQueue.size
        } else {
            repeat(targetCustomCount) { customBankQueue.add(null) }
        }
    }
    private var isQrEnabled = true

    private var currentMathIndex = 0
    private var currentPoemIndex = 0
    private var currentWordIndex = 0
    private var currentClozeIndex = 0
    private var currentCustomIndex = 0

    private var currentMathAnswer = 0
    private var currentPoemAnswer = ""
    private var currentPoem: AlarmChallengeManager.Poem? = null
    private var currentWordAnswer = ""
    private var currentWordPhonetic = ""
    private var currentWord: AlarmChallengeManager.Word? = null
    private var currentCloze: AlarmChallengeManager.ClozeQuestion? = null
    private var currentCustomQuestion: com.zhizhi.app.data.questionbank.CustomQuestion? = null
    private val selectedCustomOptions = mutableSetOf<String>()
    private var currentPoemHint = ""
    private var isAlarmFirstStagePassed = false
    private var alarmStageCooldownSeconds: Int = 0
    private var stageCooldownHandler: android.os.Handler? = null
    private var stageCooldownRemainingSeconds: Int = 0
    private var singleStageCooldownUntilMs: Long = 0L
    private var isTwoStageUnlock = false

    // Test alarm overrides (consumed by AlarmVibrationService)
    var testVibrationMode: AlarmScheduler.VibrationMode? = null
        private set
    var testVibrationIntensity: Int? = null
        private set
    var testAlarmMode: AlarmScheduler.AlarmMode? = null
        private set
    var testRingtoneUri: String? = null
        private set
    var testRingVolume: Int? = null
        private set
    var testProgressiveMinIntensity: Int? = null
        private set
    var testProgressiveMaxIntensity: Int? = null
        private set
    var testProgressiveMaxRingVolume: Int? = null
    var testAutoEscalate: Boolean? = null
        private set

    // 震动强度显示定时更新
    private var alarmIntensityUpdateRunnable: Runnable? = null

    private fun startAlarmIntensityUpdate() {
        stopAlarmIntensityUpdate()
        alarmIntensityUpdateRunnable = object : Runnable {
            override fun run() {
                if (!isAlarmOverlayActive.get()) return
                updateVibrationIntensityDisplay()
                LockWindowManager.getAlarmUiHandler().postDelayed(this, 2000)
            }
        }
        LockWindowManager.getAlarmUiHandler().post(alarmIntensityUpdateRunnable!!)
    }

    private fun stopAlarmIntensityUpdate() {
        alarmIntensityUpdateRunnable?.let { LockWindowManager.getAlarmUiHandler().removeCallbacks(it) }
        alarmIntensityUpdateRunnable = null
    }

    private fun updateVibrationIntensityDisplay() {
        val view = LockWindowManager.getAlarmView() ?: return
        val tvVib = view.findViewById<TextView>(R.id.tv_alarm_vibration_intensity)
        val tvRing = view.findViewById<TextView>(R.id.tv_alarm_ring_intensity)

        val alarmMode = AlarmVibrationService.currentAlarmMode
        val isVibrateMode = alarmMode != "RING_ONLY"
        val isRingMode = alarmMode != "VIBRATE_ONLY"

        // 震动强度
        if (isVibrateMode) {
            val vibIntensity = AlarmVibrationService.currentDisplayIntensity
            val vibMode = AlarmVibrationService.currentVibrationMode
            tvVib?.visibility = View.VISIBLE
            if (tvVib != null && vibIntensity > 0) {
                tvVib.text = if (vibMode == "progressive") {
                    "📳 震动: 渐进 $vibIntensity/10"
                } else {
                    "📳 震动: $vibIntensity/10"
                }
            }
        } else {
            tvVib?.visibility = View.GONE
        }

        // 声音强度
        if (isRingMode) {
            val ringVolume = AlarmVibrationService.currentDisplayVolume
            tvRing?.visibility = View.VISIBLE
            if (tvRing != null && ringVolume > 0) {
                tvRing.text = "🔊 声音: $ringVolume/10"
            }
        } else {
            tvRing?.visibility = View.GONE
        }
    }

    // Per-stage challenge assignment (set in showAlarmOverlay)
    private var alarmStage1Types: Set<String> = setOf("qr", "math")
    private var alarmStage2Types: Set<String> = setOf("math", "poem", "word")

    private fun resetAlarmQuestions() {
        // 阶段切换：丢弃当前未提交的题目（用户未交互，不应记入复习）
        currentPoem = null
        currentPoemAnswer = ""
        currentPoemHint = ""
        currentWord = null
        currentWordAnswer = ""
        currentWordPhonetic = ""
        currentCloze = null
        currentCustomQuestion = null
        selectedCustomOptions.clear()
        currentMathIndex = 0
        currentPoemIndex = 0
        currentWordIndex = 0
        currentClozeIndex = 0
        currentCustomIndex = 0
        rebuildCustomQueue()
    }

    // ── 会话复习数据收集 ──
    private fun settleCurrentPoem(status: String, userAnswer: String? = null) {
        val poem = currentPoem ?: return
        if (currentPoemAnswer.isEmpty()) return
        LockWindowManager.getSessionPoems().add(LockWindowManager.SessionPoemEntry(poem, status, userAnswer))
        Log.i(TAG, "settleCurrentPoem: status=$status, total=${LockWindowManager.getSessionPoems().size}, answer=${poem.down.take(10)}...")
        // 清空当前题目标记，防止重复结算
        currentPoem = null
        currentPoemAnswer = ""
        currentPoemHint = ""
    }

    private fun settleCurrentWord(status: String, userAnswer: String? = null) {
        val word = currentWord ?: return
        if (currentWordAnswer.isEmpty()) return
        LockWindowManager.getSessionWords().add(LockWindowManager.SessionWordEntry(word, status, userAnswer))
        Log.i(TAG, "settleCurrentWord: status=$status, total=${LockWindowManager.getSessionWords().size}, word=${word.text}")
        currentWord = null
        currentWordAnswer = ""
        currentWordPhonetic = ""
    }

    private fun settleCurrentCloze(status: String, userAnswer: String? = null) {
        val cloze = currentCloze ?: return
        LockWindowManager.getSessionClozes().add(LockWindowManager.SessionClozeEntry(cloze, status, userAnswer))
        Log.i(TAG, "settleCurrentCloze: status=$status, total=${LockWindowManager.getSessionClozes().size}, answer=${cloze.answer}")
        currentCloze = null
    }

    private fun settleCurrentCustom(status: String, userAnswer: String? = null) {
        val q = currentCustomQuestion ?: return
        LockWindowManager.getSessionCustomQuestions().add(LockWindowManager.SessionCustomQuestionEntry(q, status, userAnswer))
        Log.i(TAG, "settleCurrentCustom: status=$status, total=${LockWindowManager.getSessionCustomQuestions().size}, answer=${q.answer}")
        currentCustomQuestion = null
    }

    fun applyThemeToAlarmView(view: View, ctx: Context) {
        val pageBg = ThemeManager.getColorAttr(ctx, R.attr.zzPageBg)
        val surfaceBg = ThemeManager.getColorAttr(ctx, R.attr.zzSurface)
        val surface2Bg = ThemeManager.getColorAttr(ctx, R.attr.zzSurface2)
        val surfaceStrong = ThemeManager.getColorAttr(ctx, R.attr.zzSurfaceStrong)
        val textColor = ThemeManager.getColorAttr(ctx, R.attr.zzText)
        val mutedColor = ThemeManager.getColorAttr(ctx, R.attr.zzMuted)
        val accentColor = ThemeManager.getColorAttr(ctx, R.attr.zzAccent)
        val warningColor = ThemeManager.getColorAttr(ctx, R.attr.zzWarning)
        val dangerColor = ThemeManager.getColorAttr(ctx, R.attr.zzDanger)
        val successColor = ThemeManager.getColorAttr(ctx, R.attr.zzSuccess)

        // 1. 根容器背景 (必须是不透明实体色，绝不允许透明)
        val safePageBg = if (pageBg != 0 && pageBg != Color.TRANSPARENT) pageBg else ThemeManager.getColorAttr(ctx, R.attr.zzPageBg)
        view.findViewById<View>(R.id.root_container)?.setBackgroundColor(safePageBg)

        // 2. 标题与提示文字
        view.findViewById<TextView>(R.id.tv_alarm_title)?.setTextColor(textColor)
        view.findViewById<TextView>(R.id.tv_alarm_hint)?.setTextColor(mutedColor)
        view.findViewById<TextView>(R.id.tv_alarm_vibration_intensity)?.setTextColor(warningColor)
        view.findViewById<TextView>(R.id.tv_alarm_ring_intensity)?.setTextColor(successColor)
        view.findViewById<TextView>(R.id.tv_stage_cooldown)?.setTextColor(warningColor)

        // 3. 挑战卡片背景
        listOf(
            R.id.cv_alarm_math,
            R.id.cv_alarm_poem,
            R.id.cv_alarm_word,
            R.id.cv_alarm_cloze,
            R.id.cv_alarm_custom,
            R.id.cv_alarm_qr
        ).forEach { id ->
            (view.findViewById<View>(id) as? androidx.cardview.widget.CardView)?.setCardBackgroundColor(surfaceBg)
        }

        // 4. 数学题内部组件
        view.findViewById<TextView>(R.id.tv_math_progress)?.setTextColor(accentColor)
        view.findViewById<TextView>(R.id.tv_math_question)?.setTextColor(textColor)
        view.findViewById<EditText>(R.id.et_math_answer)?.apply {
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
        }
        view.findViewById<Button>(R.id.btn_math_submit)?.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
            setTextColor(Color.WHITE)
        }

        // 5. 诗词题内部组件
        view.findViewById<TextView>(R.id.tv_poem_progress)?.setTextColor(accentColor)
        view.findViewById<TextView>(R.id.tv_poem_question)?.setTextColor(textColor)
        view.findViewById<EditText>(R.id.et_poem_answer)?.apply {
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
        }
        view.findViewById<TextView>(R.id.tv_poem_hint)?.apply {
            setTextColor(mutedColor)
            setBackgroundColor(surface2Bg)
        }
        listOf(R.id.btn_poem_hint, R.id.btn_poem_skip, R.id.btn_poem_bookmark_skip).forEach { id ->
            view.findViewById<Button>(id)?.apply {
                backgroundTintList = android.content.res.ColorStateList.valueOf(surface2Bg)
                setTextColor(textColor)
            }
        }
        view.findViewById<Button>(R.id.btn_poem_submit)?.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
            setTextColor(Color.WHITE)
        }

        // 6. 单词题内部组件
        view.findViewById<TextView>(R.id.tv_word_progress)?.setTextColor(accentColor)
        view.findViewById<TextView>(R.id.tv_word_question)?.setTextColor(textColor)
        view.findViewById<EditText>(R.id.et_word_answer)?.apply {
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
        }
        view.findViewById<TextView>(R.id.tv_word_hint)?.apply {
            setTextColor(mutedColor)
            setBackgroundColor(surface2Bg)
        }
        listOf(R.id.btn_word_hint, R.id.btn_word_skip, R.id.btn_word_bookmark_skip).forEach { id ->
            view.findViewById<Button>(id)?.apply {
                backgroundTintList = android.content.res.ColorStateList.valueOf(surface2Bg)
                setTextColor(textColor)
            }
        }
        view.findViewById<Button>(R.id.btn_word_submit)?.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
            setTextColor(Color.WHITE)
        }

        // 7. 选词填空内部组件
        view.findViewById<TextView>(R.id.tv_cloze_progress)?.setTextColor(accentColor)
        view.findViewById<TextView>(R.id.tv_cloze_passage)?.setTextColor(textColor)
        view.findViewById<TextView>(R.id.tv_cloze_word_hint)?.apply {
            setTextColor(warningColor)
            setBackgroundColor(surface2Bg)
        }
        view.findViewById<Button>(R.id.btn_cloze_hint)?.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(surface2Bg)
            setTextColor(textColor)
        }
        view.findViewById<TextView>(R.id.tv_cloze_hint)?.apply {
            setTextColor(mutedColor)
            setBackgroundColor(surface2Bg)
        }
        listOf(R.id.btn_cloze_a, R.id.btn_cloze_b, R.id.btn_cloze_c, R.id.btn_cloze_d, R.id.btn_cloze_bookmark, R.id.btn_cloze_skip).forEach { id ->
            view.findViewById<Button>(id)?.apply {
                backgroundTintList = android.content.res.ColorStateList.valueOf(surface2Bg)
                setTextColor(textColor)
            }
        }

        // 8. 自定义题库内部组件
        view.findViewById<TextView>(R.id.tv_custom_progress)?.setTextColor(accentColor)
        view.findViewById<TextView>(R.id.tv_custom_stem)?.setTextColor(textColor)
        view.findViewById<Button>(R.id.btn_custom_hint)?.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(surface2Bg)
            setTextColor(textColor)
        }
        view.findViewById<TextView>(R.id.tv_custom_hint)?.apply {
            setTextColor(mutedColor)
            setBackgroundColor(surface2Bg)
        }
        listOf(R.id.btn_custom_a, R.id.btn_custom_b, R.id.btn_custom_c, R.id.btn_custom_d, R.id.btn_custom_skip).forEach { id ->
            view.findViewById<Button>(id)?.apply {
                backgroundTintList = android.content.res.ColorStateList.valueOf(surface2Bg)
                setTextColor(textColor)
            }
        }
        view.findViewById<Button>(R.id.btn_custom_submit)?.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
            setTextColor(Color.WHITE)
        }

        // 9. 扫码按钮
        view.findViewById<Button>(R.id.btn_alarm_scan)?.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
            setTextColor(Color.WHITE)
        }

        // 10. 底部关闭与稍后再响按钮
        view.findViewById<Button>(R.id.btn_alarm_close)?.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(dangerColor)
            setTextColor(Color.WHITE)
        }
        view.findViewById<Button>(R.id.btn_alarm_snooze)?.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(surfaceStrong)
            setTextColor(textColor)
        }

        // 11. 复习回顾标题与指示器
        view.findViewById<TextView>(R.id.tv_review_title)?.setTextColor(textColor)
        view.findViewById<TextView>(R.id.tv_review_cloze_indicator)?.setTextColor(textColor)
    }

    private fun initAlarmUI() {
        val view = LockWindowManager.getAlarmView() ?: return
        val effectiveCtx = view.context
        applyThemeToAlarmView(view, effectiveCtx)
        val cvQr = view.findViewById<View>(R.id.cv_alarm_qr)
        val cvMath = view.findViewById<View>(R.id.cv_alarm_math)
        val cvPoem = view.findViewById<View>(R.id.cv_alarm_poem)
        val cvWord = view.findViewById<View>(R.id.cv_alarm_word)
        val cvCloze = view.findViewById<View>(R.id.cv_alarm_cloze)
        val cvCustom = view.findViewById<View>(R.id.cv_alarm_custom)

        cvQr.visibility = if (isQrEnabled) View.VISIBLE else View.GONE
        cvMath.visibility = if (targetMathCount > 0) View.VISIBLE else View.GONE
        cvPoem.visibility = if (targetPoemCount > 0) View.VISIBLE else View.GONE
        cvWord.visibility = if (targetWordCount > 0) View.VISIBLE else View.GONE
        cvCloze.visibility = if (targetClozeCount > 0) View.VISIBLE else View.GONE
        cvCustom?.visibility = if (targetCustomCount > 0) View.VISIBLE else View.GONE

        // 按钮事件绑定
        view.findViewById<Button>(R.id.btn_alarm_scan).setOnClickListener {
            Log.d(TAG, "btn_alarm_scan clicked")
            handleAlarmScanClick()
        }
        view.findViewById<Button>(R.id.btn_alarm_scan_cancel).setOnClickListener {
            view.findViewById<View>(R.id.layout_alarm_scan).visibility = View.GONE
        }

        view.findViewById<Button>(R.id.btn_alarm_close).setOnClickListener {
            handleAlarmAction(false)
        }
        val btnSnooze = view.findViewById<Button>(R.id.btn_alarm_snooze)
        if (alarmRemainingSnoozeCount > 0) {
            btnSnooze.text = "稍后再响 ($alarmRemainingSnoozeCount)"
            btnSnooze.isEnabled = true
        } else {
            btnSnooze.text = "稍后再响 (0)"
            btnSnooze.isEnabled = false
        }
        btnSnooze.setOnClickListener {
            handleAlarmAction(true)
        }

        // --- Math ---
        val btnMathSubmit = view.findViewById<Button>(R.id.btn_math_submit)
        val etMath = view.findViewById<EditText>(R.id.et_math_answer)
        // 初始显示进度为 0/target，答完第一题后才变 1/target
        view.findViewById<TextView>(R.id.tv_math_progress).text = "数学题 (0/$targetMathCount)"
        view.findViewById<TextView>(R.id.tv_math_question).text = "点击「提交」开始第一题"
        if (targetMathCount > 0) rollMath()
        btnMathSubmit.setOnClickListener {
            val input = etMath.text.toString().trim().toIntOrNull()
            if (input == currentMathAnswer) {
                currentMathIndex++
                if (currentMathIndex >= targetMathCount) {
                    onAnyChallengePassed()
                } else {
                    rollMath()
                }
            } else {
                Toast.makeText(LockWindowManager.getAlarmContext(), "答案错误，请重算", Toast.LENGTH_SHORT).show()
                etMath.error = "答案错误！"
                etMath.setText("")
            }
        }

        // --- Poem ---
        val btnPoemSubmit = view.findViewById<Button>(R.id.btn_poem_submit)
        val btnPoemSkip = view.findViewById<Button>(R.id.btn_poem_skip)
        val etPoem = view.findViewById<EditText>(R.id.et_poem_answer)
        disablePaste(etPoem)
        view.findViewById<TextView>(R.id.tv_poem_progress).text = "诗词对接 (0/$targetPoemCount)"
        // 所有按钮默认显示（两行四按钮布局：提示 + 收藏并跳过 | 跳过 + 提交）
        if (targetPoemCount > 0) rollPoem()
        
        btnPoemSubmit.setOnClickListener {
            val input = etPoem.text.toString()
            if (AlarmChallengeManager.checkPoemMatch(input, currentPoemAnswer)) {
                settleCurrentPoem("correct", input)
                currentPoemIndex++
                if (currentPoemIndex >= targetPoemCount) {
                    onAnyChallengePassed()
                } else {
                    rollPoem()
                }
            } else {
                Toast.makeText(LockWindowManager.getAlarmContext(), "诗词错误，请重试", Toast.LENGTH_SHORT).show()
                etPoem.error = "答案错误！"
            }
        }

        // 💡 提示按钮（诗词）：在 tv_poem_hint 区域显示答案前2字，4秒后自动隐藏
        view.findViewById<Button>(R.id.btn_poem_hint).setOnClickListener {
            val tvHint = view.findViewById<TextView>(R.id.tv_poem_hint) ?: return@setOnClickListener
            if (currentPoemHint.isNotEmpty()) {
                tvHint.text = "💡 提示：答案以「${currentPoemHint}」开头"
                tvHint.visibility = View.VISIBLE
                tvHint.postDelayed({
                    tvHint.visibility = View.GONE
                    tvHint.text = ""
                }, 4000)
            }
        }

        // 📚 收藏并跳过（诗词）：只收藏，不推进进度，重新出题
        view.findViewById<Button>(R.id.btn_poem_bookmark_skip).setOnClickListener {
            val ctx = LockWindowManager.getAlarmContext() ?: return@setOnClickListener
            val tvQuestion = view.findViewById<TextView>(R.id.tv_poem_question)
            val display = tvQuestion?.text?.toString()?.split("\n")?.get(0) ?: currentPoemAnswer
            AlarmChallengeManager.bookmarkPoem(ctx, display, currentPoemAnswer)
            settleCurrentPoem("bookmarked")
            Toast.makeText(ctx, "📚 已收藏诗词，换一题", Toast.LENGTH_SHORT).show()
            rollPoem() // 只换题，不推进进度
        }

        // 跳过诗词题（只换题，不推进进度）
        btnPoemSkip.setOnClickListener {
            settleCurrentPoem("skipped")
            rollPoem() // 只切换题目，不推进进度，不会触发 onAnyChallengePassed()
        }

        // --- Word ---
        val btnWordSubmit = view.findViewById<Button>(R.id.btn_word_submit)
        val btnWordSkip = view.findViewById<Button>(R.id.btn_word_skip)
        val etWord = view.findViewById<EditText>(R.id.et_word_answer)
        disablePaste(etWord)
        view.findViewById<TextView>(R.id.tv_word_progress).text = "单词拼写 (0/$targetWordCount)"
        if (targetWordCount > 0) rollWord()

        // 🚀 键盘弹出时自动滚动，确保输入框不被键盘挡住
        setupKeyboardAwareScroll(view)

        // 跳过单词题（只换题，不推进进度）
        btnWordSkip.setOnClickListener {
            settleCurrentWord("skipped")
            rollWord() // 只切换题目，不推进进度，不会触发 onAnyChallengePassed()
        }

        // 📚 收藏并跳过（单词）：只收藏，不推进进度，重新出题
        view.findViewById<Button>(R.id.btn_word_bookmark_skip).setOnClickListener {
            val ctx = LockWindowManager.getAlarmContext() ?: return@setOnClickListener
            AlarmChallengeManager.bookmarkWord(ctx, currentWordAnswer,
                view.findViewById<TextView>(R.id.tv_word_question)?.text?.toString() ?: "",
                if (currentWordPhonetic.isNotEmpty()) "[$currentWordPhonetic]" else "")
            settleCurrentWord("bookmarked")
            Toast.makeText(ctx, "📚 已收藏单词：$currentWordAnswer，换一题", Toast.LENGTH_SHORT).show()
            rollWord() // 只换题，不推进进度
        }

        // 💡 提示音标按钮：在 tv_word_hint 区域显示音标，3秒后自动隐藏
        val btnWordHint = view.findViewById<Button>(R.id.btn_word_hint)
        btnWordHint.setOnClickListener {
            val tvHint = view.findViewById<TextView>(R.id.tv_word_hint) ?: return@setOnClickListener
            if (currentWordPhonetic.isNotEmpty()) {
                tvHint.text = "🔈 音标：[ $currentWordPhonetic ]"
                tvHint.visibility = View.VISIBLE
                tvHint.postDelayed({
                    tvHint.visibility = View.GONE
                    tvHint.text = ""
                }, 4000)
            }
        }

        btnWordSubmit.setOnClickListener {
            val input = etWord.text.toString().trim().lowercase()
            if (input == currentWordAnswer.lowercase()) {
                settleCurrentWord("correct", input)
                currentWordIndex++
                if (currentWordIndex >= targetWordCount) {
                    onAnyChallengePassed()
                } else {
                    rollWord()
                }
            } else {
                Toast.makeText(LockWindowManager.getAlarmContext(), "拼写错误，请重试", Toast.LENGTH_SHORT).show()
                etWord.error = "拼写错误！"
            }
        }

        // --- Cloze (选词填空) ---
        // 4 个选项按钮 + 跳过按钮：4 选项点击即判定正误，无需提交按钮
        val btnClozeA = view.findViewById<Button>(R.id.btn_cloze_a)
        val btnClozeB = view.findViewById<Button>(R.id.btn_cloze_b)
        val btnClozeC = view.findViewById<Button>(R.id.btn_cloze_c)
        val btnClozeD = view.findViewById<Button>(R.id.btn_cloze_d)
        val btnClozeSkip = view.findViewById<Button>(R.id.btn_cloze_skip)
        val btnClozeBookmark = view.findViewById<Button>(R.id.btn_cloze_bookmark)
        // 初始进度提示
        view.findViewById<TextView>(R.id.tv_cloze_progress).text = "选词填空 (0/$targetClozeCount)"
        if (targetClozeCount > 0) rollCloze()

        fun onClozeOptionClicked(idx: Int) {
            val cloze = currentCloze ?: return
            if (idx < 0 || idx >= cloze.options.size) return
            val picked = cloze.options[idx]
            val isCorrect = picked.equals(cloze.answer, ignoreCase = true)

            if (isCorrect) {
                // 答对 → 推进进度
                settleCurrentCloze("correct", picked)

                // 答对时注入 SM-2 复习字段: 把当前 cloze 答案对应的 word 写入
                val ctx = LockWindowManager.getAlarmContext()
                if (ctx != null) {
                    val w = AlarmChallengeManager.words.find { it.text == cloze.answer }
                    if (w != null) {
                        val phonetic = when {
                            w.uk.isNotEmpty() -> "[${w.uk}]"
                            w.us.isNotEmpty() -> "[${w.us}]"
                            else -> ""
                        }
                        AlarmChallengeManager.bookmarkWord(ctx, w.text, w.translation, phonetic, quality = 4)
                    }
                }

                currentClozeIndex++
                if (currentClozeIndex >= targetClozeCount) {
                    onAnyChallengePassed()
                } else {
                    rollCloze()
                }
            } else {
                // 答错 → 只记录，不推进进度，换一题
                settleCurrentCloze("wrong", picked)
                Toast.makeText(LockWindowManager.getAlarmContext(), "答案不正确，再试试下一题", Toast.LENGTH_SHORT).show()
                rollCloze()
            }
        }
        btnClozeA.setOnClickListener { onClozeOptionClicked(0) }
        btnClozeB.setOnClickListener { onClozeOptionClicked(1) }
        btnClozeC.setOnClickListener { onClozeOptionClicked(2) }
        btnClozeD.setOnClickListener { onClozeOptionClicked(3) }
        // Req-1: 答题时长按选项单词 → 单词详情(原单词+音标+简短翻译)+ 收藏单词
        btnClozeA.setOnLongClickListener { _ -> showWordBookmarkDialog(view.context, btnClozeA.text.toString()); true }
        btnClozeB.setOnLongClickListener { _ -> showWordBookmarkDialog(view.context, btnClozeB.text.toString()); true }
        btnClozeC.setOnLongClickListener { _ -> showWordBookmarkDialog(view.context, btnClozeC.text.toString()); true }
        btnClozeD.setOnLongClickListener { _ -> showWordBookmarkDialog(view.context, btnClozeD.text.toString()); true }
        btnClozeSkip.setOnClickListener {
            // 跳过 → 只记录并换题，不推进完成进度
            settleCurrentCloze("skipped")
            rollCloze()
        }
        btnClozeBookmark.setOnClickListener {
            // 收藏并跳过 → 保存收藏 + 记录 + 换题，不推进完成进度
            val cloze = currentCloze ?: return@setOnClickListener
            val ctx = LockWindowManager.getAlarmContext() ?: return@setOnClickListener
            AlarmChallengeManager.bookmarkCloze(ctx, cloze, null)
            Toast.makeText(ctx, "已收藏: ${cloze.answer}", Toast.LENGTH_SHORT).show()
            settleCurrentCloze("bookmarked", cloze.answer)
            rollCloze()
        }
        // 提示按钮：点击显示四个选项的中文释义
        val btnClozeHint = view.findViewById<Button>(R.id.btn_cloze_hint)
        val tvClozeHint = view.findViewById<TextView>(R.id.tv_cloze_hint)
        btnClozeHint.setOnClickListener {
            val cloze = currentCloze
            if (cloze != null) {
                val sb = StringBuilder()
                sb.appendLine("四个选项的意思：")
                val letters = listOf("A", "B", "C", "D")
                for (i in cloze.options.indices) {
                    val letter = letters.getOrNull(i) ?: (i + 1).toString()
                    val word = cloze.options[i]
                    val meaning = cloze.optionMeanings.getOrNull(i)?.takeIf { it.isNotBlank() }
                        ?: cloze.answerTranslation.takeIf { i == cloze.options.indexOf(cloze.answer) && it.isNotBlank() }
                        ?: "（暂无释义）"
                    sb.appendLine("$letter. $word — $meaning")
                }
                tvClozeHint.text = sb.toString().trim()
                tvClozeHint.visibility = View.VISIBLE
            } else {
                Toast.makeText(LockWindowManager.getAlarmContext(), "暂无题目", Toast.LENGTH_SHORT).show()
            }
        }

        // --- Custom (自定义题库) ---
        val btnCustomA = view.findViewById<Button>(R.id.btn_custom_a)
        val btnCustomB = view.findViewById<Button>(R.id.btn_custom_b)
        val btnCustomC = view.findViewById<Button>(R.id.btn_custom_c)
        val btnCustomD = view.findViewById<Button>(R.id.btn_custom_d)
        val btnCustomSkip = view.findViewById<Button>(R.id.btn_custom_skip)
        val btnCustomSubmit = view.findViewById<Button>(R.id.btn_custom_submit)
        val btnCustomHint = view.findViewById<Button>(R.id.btn_custom_hint)
        val tvCustomHint = view.findViewById<TextView>(R.id.tv_custom_hint)

        view.findViewById<TextView>(R.id.tv_custom_progress)?.text = "自定义题库 (0/$targetCustomCount)"
        if (targetCustomCount > 0) rollCustom()

        fun onCustomOptionClicked(idx: Int) {
            val q = currentCustomQuestion ?: return
            val letter = when (idx) { 0 -> "A"; 1 -> "B"; 2 -> "C"; else -> "D" }
            if (q.isMultipleChoice) {
                if (selectedCustomOptions.contains(letter)) {
                    selectedCustomOptions.remove(letter)
                } else {
                    selectedCustomOptions.add(letter)
                }
            } else {
                selectedCustomOptions.clear()
                selectedCustomOptions.add(letter)
            }
            updateCustomOptionStyles(view)
        }
        btnCustomA?.setOnClickListener { onCustomOptionClicked(0) }
        btnCustomB?.setOnClickListener { onCustomOptionClicked(1) }
        btnCustomC?.setOnClickListener { onCustomOptionClicked(2) }
        btnCustomD?.setOnClickListener { onCustomOptionClicked(3) }

        btnCustomSubmit?.setOnClickListener {
            val q = currentCustomQuestion ?: return@setOnClickListener
            val ctx = LockWindowManager.getAlarmContext()
            if (selectedCustomOptions.isEmpty()) {
                if (ctx != null) Toast.makeText(ctx, "请先选择答案后再提交", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val userAnswer = selectedCustomOptions.sorted().joinToString("")
            val isCorrect = userAnswer.equals(q.answer, ignoreCase = true)

            if (isCorrect) {
                settleCurrentCustom("correct", userAnswer)
                if (ctx != null) {
                    com.zhizhi.app.manager.questionbank.CustomQuestionBankManager.recordAttempt(
                        ctx, q.bankId, q.id, isCorrect = true, pickedOption = userAnswer
                    )
                }
                currentCustomIndex++
                if (currentCustomIndex >= targetCustomCount) {
                    onAnyChallengePassed()
                } else {
                    rollCustom()
                }
            } else {
                settleCurrentCustom("wrong", userAnswer)
                if (ctx != null) {
                    com.zhizhi.app.manager.questionbank.CustomQuestionBankManager.recordAttempt(
                        ctx, q.bankId, q.id, isCorrect = false, pickedOption = userAnswer
                    )
                    Toast.makeText(ctx, "答案不正确，已记录到错题本，换下一题", Toast.LENGTH_SHORT).show()
                }
                rollCustom()
            }
        }

        btnCustomSkip?.setOnClickListener {
            val picked = if (selectedCustomOptions.isNotEmpty()) selectedCustomOptions.sorted().joinToString("") else null
            settleCurrentCustom("skipped", picked)
            rollCustom()
        }
        btnCustomHint?.setOnClickListener {
            val q = currentCustomQuestion
            // 提示隔离铁律：仅当明确存在 hint 且非空时展示 hint，绝不展示答案或解析
            if (q != null && q.hint.isNotBlank() && tvCustomHint != null) {
                tvCustomHint.text = "💡 提示：${q.hint}"
                tvCustomHint.visibility = View.VISIBLE
            }
        }

        showAlarmActionPanel(view.findViewById(R.id.layout_alarm_actions), view.findViewById(R.id.btn_alarm_close), btnSnooze, forceHide = true)
    }

    /**
     * 键盘弹出时动态增大底部占位，让用户手动滑动即可看到输入框。
     * 不做自动滚动以免干扰用户操作（数学题/诗词输入框也会被误移）。
     *
     * [DO NOT DELETE] 必须配合 buildAlarmOverlayParams() 中不设 FLAG_LAYOUT_NO_LIMITS，
     * 否则窗口不会被键盘挤压，高度检测失效。
     */
    private fun setupKeyboardAwareScroll(view: View) {
        val sv = view.findViewById<ScrollView>(R.id.sv_alarm_challenges) ?: return
        val bottomSpacer = view.findViewById<View>(R.id.v_bottom_spacer)

        sv.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            private var fullHeight = 0

            override fun onGlobalLayout() {
                val h = sv.height
                if (h <= 0) return

                if (h > fullHeight) fullHeight = h

                val shrunk = fullHeight - h
                val keyboardVisible = shrunk > h * 0.15f

                if (keyboardVisible) {
                    // 增大底部占位 = 键盘高度 + 余量，提供足够可滚动空间让用户手动滑动
                    val targetHeight = shrunk + 200
                    bottomSpacer?.let { spacer ->
                        if (spacer.layoutParams.height != targetHeight) {
                            spacer.layoutParams.height = targetHeight
                            spacer.requestLayout()
                        }
                    }
                } else {
                    // 键盘隐藏，恢复基础底部留白（80dp → px）
                    val baseSpacerPx = (80 * view.resources.displayMetrics.density).toInt()
                    bottomSpacer?.let { spacer ->
                        if (spacer.layoutParams.height != baseSpacerPx) {
                            spacer.layoutParams.height = baseSpacerPx
                            spacer.requestLayout()
                        }
                    }
                }
            }
        })
    }

    /**
     * Req-1: 长按单词 → 弹出「单词详情」：原单词 + 音标 + 简短翻译 + 收藏单词。
     * 供选词填空答题与复习回顾的选项/单词复用。
     * 单词优先从本地词库(AlarmChallengeManager.lookupWordWithLemma)查音标与释义；
     * 收集「收藏单词」调用 bookmarkWord 写入收藏夹。
     */
    private fun showWordBookmarkDialog(ctx: Context, rawWord: String) {
        val word = rawWord.trim()
        if (word.isEmpty()) return
        val lib = AlarmChallengeManager.lookupWordWithLemma(word)
        val uk = lib?.uk.orEmpty()
        val us = lib?.us.orEmpty()
        val translation = lib?.translation ?: "（暂无释义）"
        val phonetic = buildString {
            if (uk.isNotEmpty()) append("UK: /$uk/")
            if (us.isNotEmpty()) {
                if (isNotEmpty()) append("  ")
                append("US: /$us/")
            }
        }

        val isA11y = LockWindowManager.isServiceActive()
        val dialogContext = if (isA11y) LockWindowManager.getAlarmCoreService()!! else ctx
        val d = ctx.resources.displayMetrics.density

        // Req-1: 收藏单词用一个小星星按钮：默认空星（☆），点一下变黄实心（★）并收藏；再点取消收藏。
        val star = android.widget.Button(ctx).apply {
            text = "☆"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#B0BEC5"))
            setBackgroundResource(0)
            gravity = android.view.Gravity.CENTER
            setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * d).toInt() }
            contentDescription = "收藏单词"
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())
            addView(TextView(context).apply {
                text = word
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#00838F"))
            })
            if (phonetic.isNotEmpty()) {
                addView(TextView(context).apply {
                    text = phonetic
                    textSize = 13f
                    setTextColor(Color.parseColor("#616161"))
                    setPadding(0, (6 * d).toInt(), 0, 0)
                })
            }
            addView(TextView(context).apply {
                text = translation
                textSize = 15f
                setTextColor(Color.parseColor("#212121"))
                setPadding(0, (8 * d).toInt(), 0, 0)
            })

            addView(star)
        }

        // 未命中本地词库时收藏仍写入空释义，避免收藏夹被「（暂无释义）」占位污染；
        // 展示时仍提示暂无释义，方便用户知晓该词暂不在词库。
        val wordForBookmark = word
        val translationForBookmark = lib?.translation ?: ""
        val phoneticForBookmark = phonetic

        // 星星初始态：该词已在收藏夹 → 黄色实心★，否则空星☆
        var bookmarked = AlarmChallengeManager.getBookmarkedWords(ctx)
            .any { it.word.equals(word, ignoreCase = true) }
        fun renderStar() {
            star.text = if (bookmarked) "★" else "☆"
            star.setTextColor(
                if (bookmarked) Color.parseColor("#FBC02D") else Color.parseColor("#B0BEC5")
            )
        }
        renderStar()
        star.setOnClickListener {
            bookmarked = !bookmarked
            if (bookmarked) {
                AlarmChallengeManager.bookmarkWord(ctx, wordForBookmark, translationForBookmark, phoneticForBookmark)
                Toast.makeText(ctx, "已收藏单词: $wordForBookmark", Toast.LENGTH_SHORT).show()
            } else {
                AlarmChallengeManager.removeBookmarkedWord(ctx, wordForBookmark)
                Toast.makeText(ctx, "已取消收藏: $wordForBookmark", Toast.LENGTH_SHORT).show()
            }
            renderStar()
        }

        AlertDialog.Builder(dialogContext)
            .setTitle("单词详情")
            .setView(content)
            .setPositiveButton("关闭", null)
            .create()
            .apply {
                if (isA11y) window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                else window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
            }
            .show()
    }

    private fun rollMath() {
        val view = LockWindowManager.getAlarmView() ?: return
        val pair = AlarmChallengeManager.generateMath()
        currentMathAnswer = pair.first * pair.second
        view.findViewById<TextView>(R.id.tv_math_progress).text = "数学题 ($currentMathIndex/$targetMathCount)"
        view.findViewById<TextView>(R.id.tv_math_question).text = "${pair.first} × ${pair.second} = ?"
        view.findViewById<EditText>(R.id.et_math_answer).setText("")
    }

    private fun rollPoem() {
        val view = LockWindowManager.getAlarmView() ?: return
        val poem = AlarmChallengeManager.getRandomPoem()
        if (poem == null) {
            Toast.makeText(LockWindowManager.getAlarmContext(), "诗词题库加载失败，不能通过跳过解锁", Toast.LENGTH_SHORT).show()
            return
        }
        currentPoem = poem
        currentPoemAnswer = poem.down
        // 存储提示：答案的前两个字
        currentPoemHint = poem.down.take(2)
        // 去掉 display_text 中的提示标记，只显示诗句
        val cleanUp = poem.up.replace("【请填下句】", "").replace("【请填上句】", "").trim()
        val showText = cleanUp

        view.findViewById<TextView>(R.id.tv_poem_progress).text = "诗词对接 ($currentPoemIndex/$targetPoemCount)"
        view.findViewById<TextView>(R.id.tv_poem_question).text = showText
        // 重置提示文字显示区域
        view.findViewById<TextView>(R.id.tv_poem_hint)?.let {
            it.visibility = View.GONE
            it.text = ""
        }
        view.findViewById<EditText>(R.id.et_poem_answer).setText("")
    }

    private fun rollWord() {
        val view = LockWindowManager.getAlarmView() ?: return
        val word = AlarmChallengeManager.getRandomWord()
        if (word == null) {
            Toast.makeText(LockWindowManager.getAlarmContext(), "单词题库加载失败，不能通过跳过解锁", Toast.LENGTH_SHORT).show()
            return
        }
        currentWord = word
        currentWordAnswer = word.text
        currentWordPhonetic = if (word.us.isNotEmpty()) word.us else word.uk
        view.findViewById<TextView>(R.id.tv_word_progress).text = "单词拼写 ($currentWordIndex/$targetWordCount)"

        // 只显示释义，不显示音标
        view.findViewById<TextView>(R.id.tv_word_question).text = word.translation
        // 重置提示区域和按钮
        view.findViewById<TextView>(R.id.tv_word_hint)?.let {
            it.visibility = View.GONE
            it.text = ""
        }
        view.findViewById<EditText>(R.id.et_word_answer).setText("")
    }

    private fun rollCloze() {
        val view = LockWindowManager.getAlarmView() ?: return
        val cloze = AlarmChallengeManager.generateCloze()
        if (cloze == null) {
            Toast.makeText(LockWindowManager.getAlarmContext(), "选词填空题库加载失败，不能通过跳过解锁", Toast.LENGTH_SHORT).show()
            return
        }
        currentCloze = cloze
        view.findViewById<TextView>(R.id.tv_cloze_progress).text = "选词填空 ($currentClozeIndex/$targetClozeCount)"
        // Req-1: 题目段落支持长按任意英文单词查词+收藏
        // Plan-E: 题面与复习/节选共用 cleanPassage 规范源，保持一致性
        (view.findViewById<android.widget.TextView>(R.id.tv_cloze_passage) as? WordLookupTextView)?.let { lp ->
            lp.text = AlarmChallengeManager.cleanPassage(cloze.sentence)
            lp.onWordPressed = { w -> showWordBookmarkDialog(view.context, w) }
        } ?: run { view.findViewById<TextView>(R.id.tv_cloze_passage).text = AlarmChallengeManager.cleanPassage(cloze.sentence) }
        // 提示默认隐藏，点击按钮后显示目标词+完整释义
        view.findViewById<TextView>(R.id.tv_cloze_hint)?.let {
            it.visibility = View.GONE
            it.text = ""
        }
        val btnA = view.findViewById<Button>(R.id.btn_cloze_a)
        val btnB = view.findViewById<Button>(R.id.btn_cloze_b)
        val btnC = view.findViewById<Button>(R.id.btn_cloze_c)
        val btnD = view.findViewById<Button>(R.id.btn_cloze_d)
        btnA.text = cloze.options.getOrNull(0) ?: ""
        btnB.text = cloze.options.getOrNull(1) ?: ""
        btnC.text = cloze.options.getOrNull(2) ?: ""
        btnD.text = cloze.options.getOrNull(3) ?: ""
        btnA.isEnabled = true
        btnB.isEnabled = true
        btnC.isEnabled = true
        btnD.isEnabled = true
    }

    private fun updateCustomOptionStyles(view: View) {
        val ctx = view.context
        val surface2Bg = ThemeManager.getColorAttr(ctx, R.attr.zzSurface2)
        val accentColor = ThemeManager.getColorAttr(ctx, R.attr.zzAccent)
        val textColor = ThemeManager.getColorAttr(ctx, R.attr.zzText)

        val map = mapOf(
            "A" to view.findViewById<Button>(R.id.btn_custom_a),
            "B" to view.findViewById<Button>(R.id.btn_custom_b),
            "C" to view.findViewById<Button>(R.id.btn_custom_c),
            "D" to view.findViewById<Button>(R.id.btn_custom_d)
        )
        map.forEach { (letter, btn) ->
            if (btn != null) {
                val isSelected = selectedCustomOptions.contains(letter)
                if (isSelected) {
                    btn.backgroundTintList = ColorStateList.valueOf(accentColor)
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.backgroundTintList = ColorStateList.valueOf(surface2Bg)
                    btn.setTextColor(textColor)
                }
            }
        }
    }

    private fun rollCustom() {
        val view = LockWindowManager.getAlarmView() ?: return
        val ctx = LockWindowManager.getAlarmContext() ?: return
        val bankId = customBankQueue.getOrNull(currentCustomIndex)
        val q = com.zhizhi.app.manager.questionbank.CustomQuestionBankManager.getRandomQuestion(ctx, bankId)
            ?: com.zhizhi.app.manager.questionbank.CustomQuestionBankManager.getRandomQuestion(ctx)
        if (q == null) {
            Toast.makeText(ctx, "未找到可用自定义题目，已自动完成该项", Toast.LENGTH_SHORT).show()
            currentCustomIndex = targetCustomCount
            onAnyChallengePassed()
            return
        }
        currentCustomQuestion = q
        selectedCustomOptions.clear()
        val bankName = bankId?.let { com.zhizhi.app.manager.questionbank.CustomQuestionBankManager.getBank(ctx, it)?.title } ?: "自定义题库"
        val typeTag = if (q.isMultipleChoice) "【多选】" else "【单选】"
        view.findViewById<TextView>(R.id.tv_custom_progress)?.text = "$bankName $typeTag ($currentCustomIndex/$targetCustomCount)"
        view.findViewById<TextView>(R.id.tv_custom_stem)?.text = q.stem

        // 提示安全隔离铁律：仅当明确存在 hint 且非空时显示提示按钮
        val btnHint = view.findViewById<Button>(R.id.btn_custom_hint)
        val hasHint = q.hint.isNotBlank()
        btnHint?.visibility = if (hasHint) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.tv_custom_hint)?.apply {
            visibility = View.GONE
            text = ""
        }

        val btnA = view.findViewById<Button>(R.id.btn_custom_a)
        val btnB = view.findViewById<Button>(R.id.btn_custom_b)
        val btnC = view.findViewById<Button>(R.id.btn_custom_c)
        val btnD = view.findViewById<Button>(R.id.btn_custom_d)
        btnA?.text = "A. ${q.options.getOrNull(0) ?: ""}"
        btnB?.text = "B. ${q.options.getOrNull(1) ?: ""}"
        btnC?.text = "C. ${q.options.getOrNull(2) ?: ""}"
        if (q.options.size >= 4) {
            btnD?.visibility = View.VISIBLE
            btnD?.text = "D. ${q.options.getOrNull(3) ?: ""}"
            btnD?.isEnabled = true
        } else {
            btnD?.visibility = View.GONE
        }
        btnA?.isEnabled = true
        btnB?.isEnabled = true
        btnC?.isEnabled = true
        updateCustomOptionStyles(view)
    }

    private fun disablePaste(editText: EditText) {
        editText.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem) = false
            override fun onDestroyActionMode(mode: android.view.ActionMode) {}
        }
        editText.isLongClickable = false
    }

    internal fun onAnyChallengePassed() {
        val view = LockWindowManager.getAlarmView() ?: return
        val ctx = LockWindowManager.getAlarmContext() ?: return

        if (isTwoStageUnlock && !isAlarmFirstStagePassed) {
            // Stage 1 cleared — advance to stage 2 (reset counters for fresh Stage 2 challenges)
            isAlarmFirstStagePassed = true
            AlarmScheduler.markFirstStagePassed(ctx, true)
            // 第一关通过立即将震动和铃声衰减为最微弱温和模式
            com.zhizhi.app.service.AlarmVibrationService.attenuate(ctx)
            // [Fix: two-stage counter exhaustion] 重置计数器并重新出题，让 Stage 2 有全新的数学/诗词/单词题
            resetAlarmQuestions()

            if (alarmStageCooldownSeconds > 0) {
                // 启动冷却倒计时
                stageCooldownRemainingSeconds = alarmStageCooldownSeconds
                startStageCooldown(ctx)
                syncChallengeUiByStage()
                return
            }

            // 无冷却，直接进入第二关
            val v = LockWindowManager.getAlarmView()
            if (v != null) {
                if ("math" in alarmStage2Types && targetMathCount > 0) rollMath()
                if ("poem" in alarmStage2Types && targetPoemCount > 0) rollPoem()
                if ("word" in alarmStage2Types && targetWordCount > 0) rollWord()
                if ("cloze" in alarmStage2Types && targetClozeCount > 0) rollCloze()
                if ("custom" in alarmStage2Types && targetCustomCount > 0) rollCustom()
            }
            syncChallengeUiByStage()
            Toast.makeText(ctx, "第一关通过，请完成第二关", Toast.LENGTH_SHORT).show()
            return
        }

        // All done (single-stage, or two-stage all challenges complete).
        // Commit before review/close so review time is not counted as wake time.
        if (alarmId > 0) {
            AlarmWakeStatsRecorder.completeSession(ctx, alarmId)
        }
        // If stage 1 was completed while a silent snooze timer was active, attenuate() cancels
        // that restore timer so the old stage-1 alarm cannot suddenly resume during review.
        AlarmVibrationService.attenuate(ctx)
        view.clearFocus()
        // 隐藏挑战区域
        view.findViewById<View>(R.id.cv_alarm_qr)?.visibility = View.GONE
        view.findViewById<View>(R.id.cv_alarm_math)?.visibility = View.GONE
        view.findViewById<View>(R.id.cv_alarm_poem)?.visibility = View.GONE
        view.findViewById<View>(R.id.cv_alarm_word)?.visibility = View.GONE
        view.findViewById<View>(R.id.cv_alarm_cloze)?.visibility = View.GONE
        view.findViewById<View>(R.id.cv_alarm_custom)?.visibility = View.GONE

        // 构建复习回顾卡片
        val hasReview = LockWindowManager.getSessionPoems().isNotEmpty() ||
            LockWindowManager.getSessionWords().isNotEmpty() ||
            LockWindowManager.getSessionClozes().isNotEmpty() ||
            LockWindowManager.getSessionCustomQuestions().isNotEmpty()
        Log.i(TAG, "onAnyChallengePassed: final stage done, hasReview=$hasReview, poems=${LockWindowManager.getSessionPoems().size}, words=${LockWindowManager.getSessionWords().size}, clozes=${LockWindowManager.getSessionClozes().size}, customs=${LockWindowManager.getSessionCustomQuestions().size}")
        if (hasReview) {
            // 隐藏挑战 ScrollView，显示固定头部的复习区域
            view.findViewById<View>(R.id.sv_alarm_challenges)?.visibility = View.GONE
            val reviewLayout = view.findViewById<View>(R.id.layout_alarm_review)
            // v3.1.4 fix: 仅在首次完成时构建, 避免重复设置 ViewPager2 adapter 导致 IllegalStateException
            if (reviewLayout?.visibility != View.VISIBLE) {
                buildReviewCards(view)
            }
            // 确保二次通过(如扫码)后 review 区域仍然可见
            reviewLayout?.visibility = View.VISIBLE
        }

        val layoutActions = view.findViewById<View>(R.id.layout_alarm_actions)
        val btnClose = view.findViewById<Button>(R.id.btn_alarm_close)
        val btnSnooze = view.findViewById<Button>(R.id.btn_alarm_snooze)

        if (!isTwoStageUnlock && alarmStageCooldownSeconds > 0) {
            stageCooldownRemainingSeconds = alarmStageCooldownSeconds
            singleStageCooldownUntilMs = System.currentTimeMillis() + alarmStageCooldownSeconds * 1000L
            showAlarmActionPanel(layoutActions, btnClose, btnSnooze, forceHide = true)
            startStageCooldown(ctx)
            Toast.makeText(ctx, "挑战完成！防刷手机倒计时中，稍后方可关闭", Toast.LENGTH_SHORT).show()
        } else {
            showAlarmActionPanel(layoutActions, btnClose, btnSnooze, forceHide = false)
            Toast.makeText(ctx, "验证成功，可以关闭闹钟了", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildReviewCards(view: View) {
        val containerPoems = view.findViewById<LinearLayout>(R.id.container_review_poems) ?: return
        val containerWords = view.findViewById<LinearLayout>(R.id.container_review_words) ?: return
        val containerCustom = view.findViewById<LinearLayout>(R.id.container_review_custom) ?: return
        val layoutClozePager = view.findViewById<LinearLayout>(R.id.layout_review_cloze_pager) ?: return
        val vpCloze = view.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.vp_review_cloze) ?: return
        val tvClozeIndicator = view.findViewById<TextView>(R.id.tv_review_cloze_indicator) ?: return
        val btnClozePrev = view.findViewById<Button>(R.id.btn_review_cloze_prev) ?: return
        val btnClozeNext = view.findViewById<Button>(R.id.btn_review_cloze_next) ?: return
        val layoutTabs = view.findViewById<LinearLayout>(R.id.layout_review_tabs) ?: return
        val btnTabPoem = view.findViewById<Button>(R.id.btn_review_tab_poem) ?: return
        val btnTabWord = view.findViewById<Button>(R.id.btn_review_tab_word) ?: return
        val btnTabCloze = view.findViewById<Button>(R.id.btn_review_tab_cloze) ?: return
        val btnTabCustom = view.findViewById<Button>(R.id.btn_review_tab_custom) ?: return
        val btnJumpCloze = view.findViewById<Button>(R.id.btn_review_jump_cloze) ?: return
        val ctx = view.context
        val d = ctx.resources.displayMetrics.density
        // v3.1.4 fix: 父容器 sv_review_cards (诗词/单词/自定义题库 ScrollView) 必须与 layout_review_cloze_pager 互斥,
        // 否则两者同时 VISIBLE + 都有 weight=1 会导致卡片区域被平分一半高度.
        val svReviewCards = view.findViewById<ScrollView>(R.id.sv_review_cards)

        containerPoems.removeAllViews()
        containerWords.removeAllViews()
        containerCustom.removeAllViews()

        // 构建诗词卡片 (单页 ScrollView, 不分页)
        for (entry in LockWindowManager.getSessionPoems()) {
            containerPoems.addView(buildReviewPoemCard(ctx, entry, d))
        }

        // 构建单词卡片 (单页 ScrollView, 不分页)
        for (entry in LockWindowManager.getSessionWords()) {
            containerWords.addView(buildReviewWordCard(ctx, entry, d))
        }

        // 构建自定义题库卡片 (单页 ScrollView, 不分页)
        for (entry in LockWindowManager.getSessionCustomQuestions()) {
            containerCustom.addView(buildReviewCustomCard(ctx, entry, d))
        }

        // v3.1 Bug-1: 选词填空卡片 → ViewPager2 分页
        if (LockWindowManager.getSessionClozes().isNotEmpty()) {
            vpCloze.adapter = LockWindowManager.ClozePagerAdapter(ctx, LockWindowManager.getSessionClozes().toList(), d)
            updateClozeIndicator(tvClozeIndicator, btnClozePrev, btnClozeNext, 0, LockWindowManager.getSessionClozes().size)
            vpCloze.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateClozeIndicator(tvClozeIndicator, btnClozePrev, btnClozeNext, position, LockWindowManager.getSessionClozes().size)
                }
            })
            btnClozePrev.setOnClickListener {
                val cur = vpCloze.currentItem
                if (cur > 0) vpCloze.setCurrentItem(cur - 1, true)
            }
            btnClozeNext.setOnClickListener {
                val cur = vpCloze.currentItem
                if (cur < LockWindowManager.getSessionClozes().size - 1) vpCloze.setCurrentItem(cur + 1, true)
            }
            layoutClozePager.visibility = View.VISIBLE
        } else {
            layoutClozePager.visibility = View.GONE
        }

        val hasPoems = LockWindowManager.getSessionPoems().isNotEmpty()
        val hasWords = LockWindowManager.getSessionWords().isNotEmpty()
        val hasClozes = LockWindowManager.getSessionClozes().isNotEmpty()
        val hasCustoms = LockWindowManager.getSessionCustomQuestions().isNotEmpty()
        val activeTabs = listOf(hasPoems, hasWords, hasClozes, hasCustoms).count { it }

        if (activeTabs >= 2) {
            // 两种及以上 → 显示 Tab 切换
            layoutTabs.visibility = View.VISIBLE
            btnTabPoem.visibility = if (hasPoems) View.VISIBLE else View.GONE
            btnTabWord.visibility = if (hasWords) View.VISIBLE else View.GONE
            btnTabCloze.visibility = if (hasClozes) View.VISIBLE else View.GONE
            btnTabCustom.visibility = if (hasCustoms) View.VISIBLE else View.GONE

            if (hasClozes && (hasPoems || hasWords || hasCustoms)) {
                btnJumpCloze.visibility = View.VISIBLE
                btnJumpCloze.text = "📝 选词填空 (${LockWindowManager.getSessionClozes().size} 题)"
            } else {
                btnJumpCloze.visibility = View.GONE
            }

            fun switchTab(activeView: View, activeBtn: Button, isScrollChild: Boolean) {
                val allContainers = listOf(containerPoems, containerWords, layoutClozePager, containerCustom)
                val allButtons = listOf(btnTabPoem, btnTabWord, btnTabCloze, btnTabCustom)
                allContainers.forEach { it.visibility = if (it === activeView) View.VISIBLE else View.GONE }
                allButtons.forEach { setReviewTabActive(it, it === activeBtn) }
                svReviewCards?.visibility = if (isScrollChild) View.VISIBLE else View.GONE
            }

            // 默认显示第一个非空 tab
            when {
                hasPoems -> switchTab(containerPoems, btnTabPoem, true)
                hasWords -> switchTab(containerWords, btnTabWord, true)
                hasClozes -> switchTab(layoutClozePager, btnTabCloze, false)
                else -> switchTab(containerCustom, btnTabCustom, true)
            }

            // 设置点击事件
            if (hasPoems) btnTabPoem.setOnClickListener { switchTab(containerPoems, btnTabPoem, true) }
            if (hasWords) btnTabWord.setOnClickListener { switchTab(containerWords, btnTabWord, true) }
            if (hasClozes) btnTabCloze.setOnClickListener { switchTab(layoutClozePager, btnTabCloze, false) }
            if (hasCustoms) btnTabCustom.setOnClickListener { switchTab(containerCustom, btnTabCustom, true) }
            if (hasClozes && (hasPoems || hasWords || hasCustoms)) {
                btnJumpCloze.setOnClickListener {
                    vpCloze.setCurrentItem(0, false)
                    switchTab(layoutClozePager, btnTabCloze, false)
                }
            }
        } else {
            // 仅一种 → 不显示 Tab
            layoutTabs.visibility = View.GONE
            btnJumpCloze.visibility = View.GONE
            containerPoems.visibility = if (hasPoems) View.VISIBLE else View.GONE
            containerWords.visibility = if (hasWords) View.VISIBLE else View.GONE
            layoutClozePager.visibility = if (hasClozes) View.VISIBLE else View.GONE
            containerCustom.visibility = if (hasCustoms) View.VISIBLE else View.GONE
            svReviewCards?.visibility = if (hasPoems || hasWords || hasCustoms) View.VISIBLE else View.GONE
        }

        view.findViewById<View>(R.id.layout_alarm_review)?.visibility = View.VISIBLE
        Log.i(TAG, "buildReviewCards: ${LockWindowManager.getSessionPoems().size} poems + ${LockWindowManager.getSessionWords().size} words + ${LockWindowManager.getSessionClozes().size} clozes + ${LockWindowManager.getSessionCustomQuestions().size} customs rendered")
    }

    /** v3.1 Bug-1: 更新选词填空分页指示器 + 边界箭头禁用. */
    private fun updateClozeIndicator(
        tv: TextView,
        btnPrev: Button,
        btnNext: Button,
        position: Int,
        total: Int
    ) {
        tv.text = "${position + 1} / $total"
        btnPrev.isEnabled = position > 0
        btnNext.isEnabled = position < total - 1
    }

    private fun showReviewTab(
        active: View,
        activeBtn: Button,
        others: List<View>,
        otherBtns: List<Button>
    ) {
        active.visibility = View.VISIBLE
        setReviewTabActive(activeBtn, true)
        others.forEach { it.visibility = View.GONE }
        otherBtns.forEach { setReviewTabActive(it, false) }
    }

    private fun setReviewTabActive(btn: Button, active: Boolean) {
        val ctx = btn.context
        val accent = ThemeManager.getColorAttr(ctx, R.attr.zzAccent)
        val surface2 = ThemeManager.getColorAttr(ctx, R.attr.zzSurface2)
        val text = ThemeManager.getColorAttr(ctx, R.attr.zzText)
        if (active) {
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            btn.setTextColor(Color.WHITE)
        } else {
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(surface2)
            btn.setTextColor(text)
        }
    }

    private fun buildReviewPoemCard(ctx: Context, entry: LockWindowManager.SessionPoemEntry, d: Float): View {
        val poem = entry.poem
        val cleanUp = poem.up.replace("【请填下句】", "").replace("【请填上句】", "").trim()
        val surfaceBg = ThemeManager.getColorAttr(ctx, R.attr.zzSurface)
        val accentColor = ThemeManager.getColorAttr(ctx, R.attr.zzAccent)
        val successColor = ThemeManager.getColorAttr(ctx, R.attr.zzSuccess)

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceBg)
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, (8 * d).toInt()) }

            // 上句
            addView(TextView(ctx).apply {
                text = "📜 $cleanUp"
                textSize = 14f
                setTextColor(accentColor)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, (4 * d).toInt())
            })
            // 下句
            addView(TextView(ctx).apply {
                text = "✏️ ${poem.down}"
                textSize = 14f
                setTextColor(successColor)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, (8 * d).toInt())
            })
            // 底部操作栏: 状态标签 + 收藏按钮
            val bottomRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            bottomRow.addView(buildReviewStatusBadge(ctx, entry.status, d))
            bottomRow.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            var isFav = AlarmChallengeManager.isPoemBookmarked(ctx, cleanUp, poem.down)
            val btnFav = TextView(ctx).apply {
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                fun syncState() {
                    text = if (isFav) "★ 已收藏" else "☆ 收藏"
                    setTextColor(if (isFav) Color.parseColor("#FFD54F") else Color.parseColor("#B0BEC5"))
                }
                syncState()
                setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
                setOnClickListener {
                    isFav = !isFav
                    if (isFav) {
                        AlarmChallengeManager.bookmarkPoem(ctx, cleanUp, poem.down)
                        Toast.makeText(ctx, "已收藏诗词", Toast.LENGTH_SHORT).show()
                    } else {
                        AlarmChallengeManager.removeBookmarkedPoem(ctx, cleanUp, poem.down)
                        Toast.makeText(ctx, "已取消收藏", Toast.LENGTH_SHORT).show()
                    }
                    syncState()
                }
            }
            bottomRow.addView(btnFav)
            addView(bottomRow)
        }
    }

    private fun buildReviewWordCard(ctx: Context, entry: LockWindowManager.SessionWordEntry, d: Float): View {
        val word = entry.word
        val phonetic = buildString {
            if (word.us.isNotEmpty()) append("US: /${word.us}/")
            if (word.uk.isNotEmpty()) {
                if (isNotEmpty()) append("  ")
                append("UK: /${word.uk}/")
            }
        }
        val surfaceBg = ThemeManager.getColorAttr(ctx, R.attr.zzSurface)
        val textColor = ThemeManager.getColorAttr(ctx, R.attr.zzText)
        val mutedColor = ThemeManager.getColorAttr(ctx, R.attr.zzMuted)
        val subtleColor = ThemeManager.getColorAttr(ctx, R.attr.zzSubtle)

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceBg)
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, (8 * d).toInt()) }

            // 单词
            addView(TextView(ctx).apply {
                text = word.text
                textSize = 18f
                setTextColor(textColor)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, (2 * d).toInt())
            })
            // 中文释义
            addView(TextView(ctx).apply {
                text = word.translation
                textSize = 13f
                setTextColor(mutedColor)
                setPadding(0, 0, 0, if (phonetic.isNotEmpty()) (2 * d).toInt() else (8 * d).toInt())
            })
            // 音标
            if (phonetic.isNotEmpty()) {
                addView(TextView(ctx).apply {
                    text = phonetic
                    textSize = 11f
                    setTextColor(subtleColor)
                    setPadding(0, 0, 0, (8 * d).toInt())
                })
            }
            // 底部操作栏: 状态标签 + 收藏按钮
            val bottomRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            bottomRow.addView(buildReviewStatusBadge(ctx, entry.status, d))
            bottomRow.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            var isFav = AlarmChallengeManager.isWordBookmarked(ctx, word.text)
            val btnFav = TextView(ctx).apply {
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                fun syncState() {
                    text = if (isFav) "★ 已收藏" else "☆ 收藏"
                    setTextColor(if (isFav) Color.parseColor("#FFD54F") else Color.parseColor("#B0BEC5"))
                }
                syncState()
                setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
                setOnClickListener {
                    isFav = !isFav
                    if (isFav) {
                        AlarmChallengeManager.bookmarkWord(ctx, word.text, word.translation, phonetic)
                        Toast.makeText(ctx, "已收藏单词", Toast.LENGTH_SHORT).show()
                    } else {
                        AlarmChallengeManager.removeBookmarkedWord(ctx, word.text)
                        Toast.makeText(ctx, "已取消收藏", Toast.LENGTH_SHORT).show()
                    }
                    syncState()
                }
            }
            bottomRow.addView(btnFav)
            addView(bottomRow)
        }
    }

    internal fun buildReviewClozeCard(ctx: Context, entry: LockWindowManager.SessionClozeEntry, d: Float): View {
        val cloze = entry.cloze
        val surfaceBg = ThemeManager.getColorAttr(ctx, R.attr.zzSurface)
        val textColor = ThemeManager.getColorAttr(ctx, R.attr.zzText)
        val accentColor = ThemeManager.getColorAttr(ctx, R.attr.zzAccent)
        val successColor = ThemeManager.getColorAttr(ctx, R.attr.zzSuccess)
        val dangerColor = ThemeManager.getColorAttr(ctx, R.attr.zzDanger)
        val mutedColor = ThemeManager.getColorAttr(ctx, R.attr.zzMuted)
        val subtleColor = ThemeManager.getColorAttr(ctx, R.attr.zzSubtle)

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceBg)
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, (8 * d).toInt()) }

            // 1. 题目标题: 年份 + 题号 (右上角放状态徽章)
            val titleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val headerText = if (cloze.sourceYear > 0) {
                "📅 ${cloze.sourceYear} 年 第 ${cloze.sourceQuestionNo} 题"
            } else "选词填空"
            titleRow.addView(TextView(ctx).apply {
                text = headerText
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(accentColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            titleRow.addView(buildReviewStatusBadge(ctx, entry.status, d))
            val clozeId = "kaoyan_cloze_${cloze.sourceYear}_${cloze.sourceQuestionNo.toString().padStart(2, '0')}"
            var isFav = AlarmChallengeManager.isClozeBookmarked(ctx, clozeId)
            val btnFav = TextView(ctx).apply {
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                fun syncState() {
                    text = if (isFav) "★ 已收藏" else "☆ 收藏"
                    setTextColor(if (isFav) Color.parseColor("#FFD54F") else Color.parseColor("#B0BEC5"))
                }
                syncState()
                setPadding((8 * d).toInt(), (2 * d).toInt(), 0, (2 * d).toInt())
                setOnClickListener {
                    isFav = !isFav
                    if (isFav) {
                        AlarmChallengeManager.bookmarkCloze(ctx, cloze, null)
                        Toast.makeText(ctx, "已收藏该题", Toast.LENGTH_SHORT).show()
                    } else {
                        AlarmChallengeManager.removeBookmarkedCloze(ctx, clozeId, null)
                        Toast.makeText(ctx, "已取消收藏", Toast.LENGTH_SHORT).show()
                    }
                    syncState()
                }
            }
            titleRow.addView(btnFav)
            addView(titleRow)
            addView(buildReviewDivider(ctx, d))

            // 2. 题目(英文) + 题目翻译(中文) 紧贴
            addView(WordLookupTextView(ctx).apply {
                text = AlarmChallengeManager.cleanPassage(cloze.sentence)
                textSize = 14f
                setTextColor(textColor)
                setLineSpacing(0f, 1.2f)
                setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
                onWordPressed = { w -> showWordBookmarkDialog(ctx, w) }
            })

            // 3. 四个选项 (字母加粗 + 单词 + 翻译,左右并列;正解打勾)
            if (cloze.options.isNotEmpty()) {
                val letters = listOf("A", "B", "C", "D")
                addView(TextView(ctx).apply {
                    text = "📋 选项"
                    textSize = 12f
                    setTextColor(mutedColor)
                    setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
                })
                cloze.options.forEachIndexed { idx, word ->
                    val letter = letters.getOrNull(idx) ?: (idx + 1).toString()
                    val rawMeaning = cloze.optionMeanings.getOrNull(idx).orEmpty()
                    val cleanedMeaning = AlarmChallengeManager.cleanTranslation(rawMeaning)
                    val trans: String? = when {
                        cleanedMeaning.isNotBlank() && cleanedMeaning != "（无释义）" -> cleanedMeaning
                        else -> AlarmChallengeManager.lookupWordWithLemma(word)?.translation
                    }
                    val isCorrect = word == cloze.answer
                    val isUserWrongPick = entry.userAnswer != null &&
                        entry.userAnswer == word && entry.userAnswer != cloze.answer
                    val marker = when {
                        isCorrect -> " ✓"
                        isUserWrongPick -> " ✗"
                        else -> ""
                    }
                    val optionColor = when {
                        isCorrect -> successColor
                        isUserWrongPick -> dangerColor
                        else -> textColor
                    }
                    val optionWord = word
                    val optionBlock = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding((8 * d).toInt(), (2 * d).toInt(), 0, (4 * d).toInt())
                        isLongClickable = true
                        setOnLongClickListener { _ ->
                            showWordBookmarkDialog(ctx, optionWord)
                            true
                        }
                    }
                    val headerLine = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    headerLine.addView(TextView(ctx).apply {
                        text = "$letter."
                        textSize = 14f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(accentColor)
                    })
                    headerLine.addView(TextView(ctx).apply {
                        text = "  $word$marker"
                        textSize = 14f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(optionColor)
                    })
                    optionBlock.addView(headerLine)
                    if (trans != null) {
                        optionBlock.addView(TextView(ctx).apply {
                            text = "  $trans"
                            textSize = 12f
                            setTextColor(mutedColor)
                            setPadding((16 * d).toInt(), 0, 0, 0)
                        })
                    }
                    addView(optionBlock)
                }
            }
            addView(buildReviewDivider(ctx, d))

            // 4. 节选翻译
            val excerptPairs = AlarmChallengeManager.buildExcerptPairs(cloze)
            if (excerptPairs.isNotEmpty()) {
                addView(TextView(ctx).apply {
                    text = "📝 节选翻译"
                    textSize = 12f
                    setTextColor(accentColor)
                    setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
                })
                excerptPairs.forEach { (en, zh) ->
                    val pair = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding((8 * d).toInt(), (2 * d).toInt(), 0, (2 * d).toInt())
                    }
                    pair.addView(WordLookupTextView(ctx).apply {
                        text = en
                        textSize = 12f
                        setTextColor(textColor)
                        onWordPressed = { w -> showWordBookmarkDialog(ctx, w) }
                    })
                    pair.addView(TextView(ctx).apply {
                        text = "→ $zh"
                        textSize = 12f
                        setTextColor(mutedColor)
                    })
                    addView(pair)
                }
            }
            addView(buildReviewDivider(ctx, d))

            // 5. 答案 + 精讲
            val answerTrans = cloze.answerTranslation.ifEmpty {
                AlarmChallengeManager.words.find { it.text == cloze.answer }?.translation ?: ""
            }
            val answerLine = if (answerTrans.isNotEmpty()) {
                "✏️ 答案: ${cloze.answer}（${answerTrans}）"
            } else {
                "✏️ 答案: ${cloze.answer}"
            }
            addView(WordLookupTextView(ctx).apply {
                text = answerLine
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(successColor)
                setPadding(0, (2 * d).toInt(), 0, (4 * d).toInt())
                onWordPressed = { w -> showWordBookmarkDialog(ctx, w) }
            })
            if (cloze.grammarExplanation.isNotEmpty()) {
                val cleanedExplanation = AlarmChallengeManager.cleanTranslation(cloze.grammarExplanation)
                addView(WordLookupTextView(ctx).apply {
                    text = "📖 详解\n$cleanedExplanation"
                    textSize = 11f
                    setTextColor(mutedColor)
                    setPadding(0, 0, 0, (4 * d).toInt())
                    onWordPressed = { w -> showWordBookmarkDialog(ctx, w) }
                })
            }

            // 6. 出处
            if (cloze.sourceYear > 0) {
                val repo = if (cloze.sourceRepo.isNotEmpty()) " · ${cloze.sourceRepo}" else ""
                addView(TextView(ctx).apply {
                    text = "📅 ${cloze.sourceYear} 年考研英语一 第 ${cloze.sourceQuestionNo} 题$repo"
                    textSize = 10f
                    setTextColor(subtleColor)
                    setPadding(0, (2 * d).toInt(), 0, (4 * d).toInt())
                })
            }

            // 7. 错答 (仅在错答时)
            if (entry.userAnswer != null && entry.userAnswer != cloze.answer) {
                val userTrans = AlarmChallengeManager.lookupWordWithLemma(entry.userAnswer)?.translation
                    ?: AlarmChallengeManager.words.find { it.text == entry.userAnswer }?.translation
                val userLine = if (userTrans != null) {
                    "❌ 你选了: ${entry.userAnswer}（$userTrans）"
                } else {
                    "❌ 你选了: ${entry.userAnswer}"
                }
                addView(WordLookupTextView(ctx).apply {
                    text = userLine
                    textSize = 12f
                    setTextColor(dangerColor)
                    setPadding(0, (2 * d).toInt(), 0, (4 * d).toInt())
                    onWordPressed = { w -> showWordBookmarkDialog(ctx, w) }
                })
            }
        }
    }

    internal fun buildReviewCustomCard(ctx: Context, entry: LockWindowManager.SessionCustomQuestionEntry, d: Float): View {
        val q = entry.question
        val surfaceBg = ThemeManager.getColorAttr(ctx, R.attr.zzSurface)
        val textColor = ThemeManager.getColorAttr(ctx, R.attr.zzText)
        val accentColor = ThemeManager.getColorAttr(ctx, R.attr.zzAccent)
        val successColor = ThemeManager.getColorAttr(ctx, R.attr.zzSuccess)
        val dangerColor = ThemeManager.getColorAttr(ctx, R.attr.zzDanger)
        val mutedColor = ThemeManager.getColorAttr(ctx, R.attr.zzMuted)

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceBg)
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, (8 * d).toInt()) }

            // 1. 标题行 + 状态标签
            val titleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            titleRow.addView(TextView(ctx).apply {
                text = "📚 自定义挑战"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(accentColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            titleRow.addView(buildReviewStatusBadge(ctx, entry.status, d))
            var isFav = q.isFavorite
            val btnFav = TextView(ctx).apply {
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                fun syncState() {
                    text = if (isFav) "★ 已收藏" else "☆ 收藏"
                    setTextColor(if (isFav) Color.parseColor("#FFD54F") else Color.parseColor("#B0BEC5"))
                }
                syncState()
                setPadding((8 * d).toInt(), (2 * d).toInt(), 0, (2 * d).toInt())
                setOnClickListener {
                    isFav = com.zhizhi.app.manager.questionbank.CustomQuestionBankManager.toggleFavorite(ctx, q.bankId, q.id)
                    syncState()
                    Toast.makeText(ctx, if (isFav) "已收藏自定义题" else "已取消收藏", Toast.LENGTH_SHORT).show()
                }
            }
            titleRow.addView(btnFav)
            addView(titleRow)
            addView(buildReviewDivider(ctx, d))

            // 2. 题干
            addView(TextView(ctx).apply {
                text = q.stem
                textSize = 14f
                setTextColor(textColor)
                setLineSpacing(0f, 1.2f)
                setPadding(0, (2 * d).toInt(), 0, (4 * d).toInt())
            })

            // 3. 选项
            val letters = listOf("A", "B", "C", "D")
            q.options.forEachIndexed { idx, optText ->
                val letter = letters.getOrNull(idx) ?: "A"
                val isCorrect = letter.equals(q.answer, ignoreCase = true)
                val isUserWrongPick = entry.status == "wrong" && (entry.userAnswer == optText || entry.userAnswer == letter)
                val marker = when {
                    isCorrect -> " ✓"
                    isUserWrongPick -> " ✗"
                    else -> ""
                }
                val optColor = when {
                    isCorrect -> successColor
                    isUserWrongPick -> dangerColor
                    else -> textColor
                }
                addView(TextView(ctx).apply {
                    text = "$letter. $optText$marker"
                    textSize = 13f
                    setTypeface(null, if (isCorrect || isUserWrongPick) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(optColor)
                    setPadding((8 * d).toInt(), (2 * d).toInt(), 0, (2 * d).toInt())
                })
            }
            addView(buildReviewDivider(ctx, d))

            // 4. 正确答案
            addView(TextView(ctx).apply {
                text = "✏️ 答案：选项 ${q.answer}"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(successColor)
                setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
            })

            // 5. 解析（若有）
            if (q.explanation.isNotBlank()) {
                addView(TextView(ctx).apply {
                    text = "💡 解析：${q.explanation}"
                    textSize = 12f
                    setTextColor(mutedColor)
                    setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
                })
            }
        }
    }

    /** 复习卡片细分隔线, 1dp 引用 zzLine 主题色. */
    private fun buildReviewDivider(ctx: Context, d: Float): View {
        val lineColor = ThemeManager.getColorAttr(ctx, R.attr.zzLine)
        return View(ctx).apply {
            setBackgroundColor(lineColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()
            ).apply {
                setMargins(0, (4 * d).toInt(), 0, (4 * d).toInt())
            }
        }
    }



    private fun buildReviewStatusBadge(ctx: Context, status: String, d: Float): TextView {
        return TextView(ctx).apply {
            val (text, bgColor) = when (status) {
                "correct" -> "✅ 正确" to Color.parseColor("#2E7D32")
                "skipped" -> "⏭ 跳过" to Color.parseColor("#546E7A")
                "bookmarked" -> "🔖 已收藏" to Color.parseColor("#E65100")
                "wrong" -> "❌ 错误" to Color.parseColor("#C62828")
                else -> status to Color.parseColor("#757575")
            }
            setText(text)
            textSize = 10f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 8f * d
            }
            setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }


    private fun startStageCooldown(ctx: Context) {
        stageCooldownHandler?.removeCallbacksAndMessages(null)
        stageCooldownHandler = stageCooldownHandler ?: android.os.Handler(android.os.Looper.getMainLooper())

        val runnable = object : Runnable {
            override fun run() {
                stageCooldownRemainingSeconds--
                if (stageCooldownRemainingSeconds > 0) {
                    // 更新倒计时 UI
                    updateCooldownDisplay(ctx)
                    stageCooldownHandler?.postDelayed(this, 1000)
                } else {
                    stageCooldownHandler?.removeCallbacksAndMessages(null)
                    updateCooldownDisplay(ctx)
                    if (isTwoStageUnlock) {
                        // 冷却结束，显示第二关
                        // 为第二关重新出题
                        val v = LockWindowManager.getAlarmView()
                        if (v != null) {
                            if ("math" in alarmStage2Types && targetMathCount > 0) rollMath()
                            if ("poem" in alarmStage2Types && targetPoemCount > 0) rollPoem()
                            if ("word" in alarmStage2Types && targetWordCount > 0) rollWord()
                            if ("cloze" in alarmStage2Types && targetClozeCount > 0) rollCloze()
                            if ("custom" in alarmStage2Types && targetCustomCount > 0) rollCustom()
                        }
                        syncChallengeUiByStage()
                        Toast.makeText(ctx, "冷却结束，请完成第二关", Toast.LENGTH_SHORT).show()
                    } else {
                        // 单次模式：防刷手机延迟结束，解除按钮隐藏
                        singleStageCooldownUntilMs = 0L
                        val v = LockWindowManager.getAlarmView()
                        if (v != null) {
                            val layoutActions = v.findViewById<View>(R.id.layout_alarm_actions)
                            val btnClose = v.findViewById<Button>(R.id.btn_alarm_close)
                            val btnSnooze = v.findViewById<Button>(R.id.btn_alarm_snooze)
                            showAlarmActionPanel(layoutActions, btnClose, btnSnooze, forceHide = false)
                        }
                        Toast.makeText(ctx, "倒计时结束，可以关闭闹钟了", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        stageCooldownHandler?.postDelayed(runnable, 1000)
        updateCooldownDisplay(ctx)
    }

    private fun updateCooldownDisplay(ctx: Context) {
        val view = LockWindowManager.getAlarmView() ?: return
        val tvCooldown = view.findViewById<TextView>(R.id.tv_stage_cooldown)
        val tvReviewCooldown = view.findViewById<TextView>(R.id.tv_review_cooldown)
        if (stageCooldownRemainingSeconds > 0) {
            val cooldownText = if (isTwoStageUnlock) {
                "⏳ 第二关冷却中... ${stageCooldownRemainingSeconds} 秒后开放\n可以先去刷牙、洗脸，挑战界面不会关闭"
            } else {
                "⏳ 防刷手机倒计时中... ${stageCooldownRemainingSeconds} 秒后方可关闭闹钟\n可以先去刷牙、洗脸，让大脑清醒一下"
            }
            tvCooldown?.apply {
                visibility = View.VISIBLE
                text = cooldownText
            }
            tvReviewCooldown?.apply {
                visibility = View.VISIBLE
                text = cooldownText
            }
        } else {
            tvCooldown?.visibility = View.GONE
            tvReviewCooldown?.visibility = View.GONE
        }
    }

    private fun syncChallengeUiByStage() {
        val view = LockWindowManager.getAlarmView() ?: return

        // [Fix: cooldown persistence] 冷却计时器是本地变量，覆盖层被重建时会丢失。
        // 通过持久化时间戳恢复剩余冷却时间。
        if (isTwoStageUnlock && isAlarmFirstStagePassed && alarmStageCooldownSeconds > 0) {
            if (stageCooldownRemainingSeconds <= 0) {
                val ctx = LockWindowManager.getAlarmContext()
                if (ctx != null) {
                    val passedAt = AlarmScheduler.getFirstStagePassedAt(ctx)
                    if (passedAt > 0L) {
                        val elapsedSec = ((System.currentTimeMillis() - passedAt) / 1000L).toInt()
                        val remaining = alarmStageCooldownSeconds - elapsedSec
                        if (remaining > 0) {
                            stageCooldownRemainingSeconds = remaining
                            startStageCooldown(ctx)
                        }
                    }
                }
            }
            if (stageCooldownRemainingSeconds > 0) {
                view.findViewById<View>(R.id.cv_alarm_qr)?.visibility = View.GONE
                view.findViewById<View>(R.id.cv_alarm_math)?.visibility = View.GONE
                view.findViewById<View>(R.id.cv_alarm_poem)?.visibility = View.GONE
                view.findViewById<View>(R.id.cv_alarm_word)?.visibility = View.GONE
                // Req-4 fix: 冷却期隐藏所有解锁方式（含选词填空），冷却结束后再显示
                view.findViewById<View>(R.id.cv_alarm_cloze)?.visibility = View.GONE
                view.findViewById<View>(R.id.cv_alarm_custom)?.visibility = View.GONE
                LockWindowManager.getAlarmContext()?.let { updateCooldownDisplay(it) }
                return
            }
        }

        // [Fix: a11y overlay crash] 长按电源键调出关机菜单后，
        // reassertAlarmOverlayOverSystemDialog 会修改窗口类型/状态。
        // 此时 visibility 变更会导致 ViewRootImpl.scrollToRectOrFocus 在布局传递中
        // 因焦点 View 层级不一致而崩溃（IllegalArgumentException: parameter must be a descendant）。
        // 必须在 visibility 变更前清空焦点，并在 post 中重新请求焦点。
        view.clearFocus()

        if (!isTwoStageUnlock) {
            // 单次模式：检查防刷手机倒计时恢复
            if (singleStageCooldownUntilMs > 0L) {
                val remaining = ((singleStageCooldownUntilMs - System.currentTimeMillis()) / 1000L).toInt()
                if (remaining > 0) {
                    stageCooldownRemainingSeconds = remaining
                    val ctx = LockWindowManager.getAlarmContext()
                    if (ctx != null) {
                        startStageCooldown(ctx)
                        showAlarmActionPanel(view.findViewById(R.id.layout_alarm_actions), view.findViewById(R.id.btn_alarm_close), view.findViewById(R.id.btn_alarm_snooze), forceHide = true)
                    }
                } else {
                    singleStageCooldownUntilMs = 0L
                    stageCooldownRemainingSeconds = 0
                }
            }
            return
        }

        if (!isAlarmFirstStagePassed) {
            // ── Stage 1: show challenges assigned to stage 1 ──
            val s1 = alarmStage1Types
            view.findViewById<View>(R.id.cv_alarm_qr)?.visibility = if ("qr" in s1 && isQrEnabled) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.cv_alarm_math)?.visibility = if ("math" in s1 && targetMathCount > 0) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.cv_alarm_poem)?.visibility = if ("poem" in s1 && targetPoemCount > 0) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.cv_alarm_word)?.visibility = if ("word" in s1 && targetWordCount > 0) View.VISIBLE else View.GONE
            // Req-4 fix: 第一关解锁方式也按 stage1 配置显隐选词填空
            view.findViewById<View>(R.id.cv_alarm_cloze)?.visibility = if ("cloze" in s1 && targetClozeCount > 0) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.cv_alarm_custom)?.visibility = if ("custom" in s1 && targetCustomCount > 0) View.VISIBLE else View.GONE

            // Hide stage-2 challenges
            if ("math" !in s1) view.findViewById<View>(R.id.cv_alarm_math)?.visibility = View.GONE
            if ("poem" !in s1) view.findViewById<View>(R.id.cv_alarm_poem)?.visibility = View.GONE
            if ("word" !in s1) view.findViewById<View>(R.id.cv_alarm_word)?.visibility = View.GONE
            if ("cloze" !in s1) view.findViewById<View>(R.id.cv_alarm_cloze)?.visibility = View.GONE
            if ("custom" !in s1) view.findViewById<View>(R.id.cv_alarm_custom)?.visibility = View.GONE

            // If no stage-1 challenge is configured, skip to stage 2
            val hasStage1 = ("qr" in s1 && isQrEnabled) ||
                ("math" in s1 && targetMathCount > 0) ||
                ("poem" in s1 && targetPoemCount > 0) ||
                ("word" in s1 && targetWordCount > 0) ||
                ("cloze" in s1 && targetClozeCount > 0) ||
                ("custom" in s1 && targetCustomCount > 0)
            if (!hasStage1) {
                isAlarmFirstStagePassed = true
                LockWindowManager.getAlarmContext()?.let { AlarmScheduler.markFirstStagePassed(it, true) }
                syncChallengeUiByStage()
                return
            }

            showAlarmActionPanel(view.findViewById(R.id.layout_alarm_actions),
                view.findViewById(R.id.btn_alarm_close),
                view.findViewById(R.id.btn_alarm_snooze), forceHide = true)
            return
        }

        // ── Stage 2: show challenges assigned to stage 2 (with remaining questions) ──
        val s2 = alarmStage2Types
        val stage2QrVisible = isStage2QrEnabled(s2, isQrEnabled)
        view.findViewById<View>(R.id.cv_alarm_qr)?.visibility = if (stage2QrVisible) View.VISIBLE else View.GONE
        val mathRemaining = "math" in s2 && targetMathCount > 0 && currentMathIndex < targetMathCount
        view.findViewById<View>(R.id.cv_alarm_math)?.visibility = if (mathRemaining) View.VISIBLE else View.GONE
        val poemRemaining = "poem" in s2 && targetPoemCount > 0 && currentPoemIndex < targetPoemCount
        view.findViewById<View>(R.id.cv_alarm_poem)?.visibility = if (poemRemaining) View.VISIBLE else View.GONE
        val wordRemaining = "word" in s2 && targetWordCount > 0 && currentWordIndex < targetWordCount
        view.findViewById<View>(R.id.cv_alarm_word)?.visibility = if (wordRemaining) View.VISIBLE else View.GONE
        // Req-4 fix: 第二关冷却结束后按 stage2 配置显隐选词填空
        val clozeRemaining = "cloze" in s2 && targetClozeCount > 0 && currentClozeIndex < targetClozeCount
        view.findViewById<View>(R.id.cv_alarm_cloze)?.visibility = if (clozeRemaining) View.VISIBLE else View.GONE
        val customRemaining = "custom" in s2 && targetCustomCount > 0 && currentCustomIndex < targetCustomCount
        view.findViewById<View>(R.id.cv_alarm_custom)?.visibility = if (customRemaining) View.VISIBLE else View.GONE

        val hasAnyStage2 = stage2QrVisible || mathRemaining || poemRemaining || wordRemaining || clozeRemaining || customRemaining
        if (!hasAnyStage2) {
            Log.i(TAG, "syncChallengeUiByStage: no stage 2 challenge remaining, advancing to passed")
            onAnyChallengePassed()
            return
        }

        showAlarmActionPanel(view.findViewById(R.id.layout_alarm_actions),
            view.findViewById(R.id.btn_alarm_close),
            view.findViewById(R.id.btn_alarm_snooze), forceHide = true)

        // [Fix: scrollToRectOrFocus crash] 不再使用 post + requestFocus 模式。
        // posted 的焦点请求可能在视图层级过渡期触发 ViewRootImpl.draw →
        // scrollToRectOrFocus 在不一致的父链上崩溃（IllegalArgumentException:
        // parameter must be a descendant of this view）。
        // 焦点由调用方（showAlarmOverlay / showAlarmOverlayIfHidden）统一在视图 attach 后处理。
        // 开头的 clearFocus() 仍然保留以清除旧焦点状态。
    }

    internal fun isStage2QrEnabled(stage2Types: Set<String>, isQrEnabled: Boolean): Boolean {
        return "qr" in stage2Types && isQrEnabled
    }

    /**
     * 扫码成功后由 AlarmActivity 调用，让覆盖层显示"挑战完成"状态（关闭/暂停按钮）。
     */
    fun onScanChallengePassed() {
        if (!isAlarmOverlayActive.get()) return
        val ctx = LockWindowManager.getAlarmContext() ?: return

        if (isTwoStageUnlock && !isAlarmFirstStagePassed) {
            isAlarmFirstStagePassed = true
            AlarmScheduler.markFirstStagePassed(ctx, true)
            // 第一关通过立即将震动和铃声衰减为最微弱温和模式
            com.zhizhi.app.service.AlarmVibrationService.attenuate(ctx)
            // [Fix: two-stage counter exhaustion] 重置计数器并重新出题
            resetAlarmQuestions()

            if (alarmStageCooldownSeconds > 0) {
                // 启动冷却倒计时
                stageCooldownRemainingSeconds = alarmStageCooldownSeconds
                startStageCooldown(ctx)
                syncChallengeUiByStage()
                return
            }

            // 无冷却，直接进入第二关
            val v = LockWindowManager.getAlarmView()
            if (v != null) {
                if ("math" in alarmStage2Types && targetMathCount > 0) rollMath()
                if ("poem" in alarmStage2Types && targetPoemCount > 0) rollPoem()
                if ("word" in alarmStage2Types && targetWordCount > 0) rollWord()
                if ("cloze" in alarmStage2Types && targetClozeCount > 0) rollCloze()
                if ("custom" in alarmStage2Types && targetCustomCount > 0) rollCustom()
            }
            syncChallengeUiByStage()
            Toast.makeText(ctx, "扫码通过第一关，请继续完成第二关", Toast.LENGTH_SHORT).show()
            return
        }

        onAnyChallengePassed()
    }

    fun showAlarmOverlayIfHidden() {
        if (!isAlarmOverlayActive.get()) return
        if (isAlarmScanInProgress) return
        // 清除扫码穿透模式，恢复正常触摸拦截
        if (isAlarmOverlayPassthrough) {
            setAlarmOverlayPassthrough(false)
        }

        // [内嵌模式] 覆盖层在 Activity 内（锁屏上显示时），不需要添加到 WindowManager。
        // 通过运行时 layoutParams 类型判断是否为内嵌模式。
        val isInline = LockWindowManager.getAlarmView() != null && LockWindowManager.getAlarmView()?.layoutParams !is WindowManager.LayoutParams
        if (isInline) {
            Log.d(TAG, "showAlarmOverlayIfHidden: inline mode, skipping WindowManager")
            return
        }

        // [防御性代码] 即使 isAlarmOverlayAttached 为 true，也要检查 view 的 parent 是否不为空。
        // 某些 OEM（如 Vivo）在锁屏解锁过程中会把覆盖层从 WindowManager 移除但不通知我们。
        // 参见 Issue_Tracker 05-13
        // 注意：此时 inline 检测已在前面完成并 return，此处只需检查非内嵌视图的 parent 状态
        if (isAlarmOverlayAttached && LockWindowManager.getAlarmView()?.parent != null) return
        val ctx = LockWindowManager.getAlarmContext() ?: return
        val isA11y = (LockWindowManager.getAlarmCoreService() != null || CoreService.instance != null) && LockWindowManager.isServiceActive()
        val targetWM = if (isA11y && (LockWindowManager.getAlarmCoreService() != null || CoreService.instance != null)) {
            (LockWindowManager.getAlarmCoreService() ?: CoreService.instance)!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        } else {
            LockWindowManager.getAlarmWindowManager() ?: return
        }
        val params = buildAlarmOverlayParams(isA11y)
        try {
            // 如果 view 已有 parent，先移除再添加（防重复添加异常）
            if (LockWindowManager.getAlarmView()?.parent != null) {
                try {
                    // [Fix: a11y overlay crash] removeView 前清空焦点
                    LockWindowManager.getAlarmView()?.clearFocus()
                    (LockWindowManager.getAlarmView()?.parent as? android.view.ViewManager)?.removeView(LockWindowManager.getAlarmView())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove alarm view", e)
                }
            }
            targetWM.addView(LockWindowManager.getAlarmView(), params)
            mAlarmActiveWindowManager = targetWM
            isAlarmOverlayAttached = true
            try {
                LockWindowManager.getAlarmView()?.isFocusable = true
                LockWindowManager.getAlarmView()?.isFocusableInTouchMode = true
                LockWindowManager.getAlarmView()?.requestFocus()
            } catch (e: Exception) {
                Log.w(TAG, "showAlarmOverlayIfHidden: requestFocus failed", e)
            }
            Log.i(TAG, "showAlarmOverlayIfHidden: overlay re-attached successfully")
        } catch (e: Exception) {
            Log.e(TAG, "showAlarmOverlayIfHidden failed", e)
        }
    }

    fun showAlarmOverlayInlineInActivity(activity: android.app.Activity, controller: LockWindowManager.AlarmOverlayController): Boolean {
        if (!isAlarmOverlayActive.get()) return false
        val view = LockWindowManager.getAlarmView() ?: return false
        return try {
            // [Fix: REHOST_INLINE_AFTER_SCAN_FAILED] 窗口模式下 view.parent 是 ViewGroup 而非 ViewManager，
            // 因此 (parent as? ViewManager) 会返回 null 导致 removeView 被跳过，进而 setContentView 崩溃。
            // 改用 mAlarmActiveWindowManager?.removeView() 确保正确从 WindowManager 移除。
            if (view.parent != null && mAlarmActiveWindowManager != null) {
                // [Fix: a11y overlay crash] removeView 前清空焦点
                view.clearFocus()
                mAlarmActiveWindowManager?.removeView(view)
            }
            mAlarmController = controller
            activity.setContentView(view)
            // [Fix: 扫码后覆盖层重新挂载] 重新设置沉浸模式，防止导航栏露出导致上滑退出
            activity.window?.decorView?.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            isAlarmOverlayAttached = true
            // [Fix: scrollToRectOrFocus crash] setContentView 后视图已 attach，
            // 但焦点请求在后续 draw 中可能触发 scrollToRectOrFocus 崩溃。
            // 使用 post 延后焦点请求到布局完成后，并捕获异常防止崩溃传播。
            view.post {
                try {
                    view.isFocusable = true
                    view.isFocusableInTouchMode = true
                    view.requestFocus()
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "showAlarmOverlayInlineInActivity: requestFocus crashed (view hierarchy inconsistency)", e)
                    LogExporter.logEvent("AlarmOverlay", "REHOST_FOCUS_CRASH error=${e.javaClass.simpleName}:${e.message}")
                } catch (e: Exception) {
                    Log.w(TAG, "showAlarmOverlayInlineInActivity: requestFocus failed", e)
                }
            }
            LogExporter.logEvent("AlarmOverlay", "REHOST_INLINE_AFTER_SCAN ctx=${activity.javaClass.simpleName} alarmId=$alarmId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "showAlarmOverlayInlineInActivity failed", e)
            LogExporter.logEvent("AlarmOverlay", "REHOST_INLINE_AFTER_SCAN_FAILED error=${e.javaClass.simpleName}:${e.message}")
            false
        }
    }

    /**
     * 临时隐藏闹钟覆盖层（不移除 view 引用），用于相机权限请求等场景。
     * 系统权限弹窗会被 TYPE_APPLICATION_OVERLAY 覆盖，必须先隐藏覆盖层用户才能操作弹窗。
     * 权限回调后调用 showAlarmOverlayIfHidden() 恢复。
     *
     * 对于扫码场景，使用 setAlarmOverlayPassthrough() 保持覆盖层在窗口栈中，
     * 以维持 TYPE_ACCESSIBILITY_OVERLAY 的 z-order 阻挡系统对话框（如关机菜单）。
     */
    fun hideAlarmOverlayForPermission() {
        if (!isAlarmOverlayActive.get()) return
        // [内嵌模式] View 在 Activity 的 contentView 中，不需要从 WindowManager 移除
        if (LockWindowManager.isCurrentOverlayInline()) {
            Log.d(TAG, "hideAlarmOverlayForPermission: inline mode, skipping WindowManager removeView")
            return
        }
        try {
            if (LockWindowManager.getAlarmView() != null && mAlarmActiveWindowManager != null && LockWindowManager.getAlarmView()?.parent != null) {
                // [Fix: a11y overlay crash] removeView 前清空焦点
                LockWindowManager.getAlarmView()?.clearFocus()
                mAlarmActiveWindowManager?.removeView(LockWindowManager.getAlarmView())
                isAlarmOverlayAttached = false
                Log.d(TAG, "hideAlarmOverlayForPermission: overlay removed for permission dialog")
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 扫码期间将覆盖层设为穿透模式：保持窗口在 z-order 中阻挡系统对话框（关机菜单等），
     * 但触摸事件穿透到下方的扫码 Activity。扫码结束后调用 showAlarmOverlayIfHidden() 恢复。
     */
    fun setAlarmOverlayPassthrough(enable: Boolean) {
        isAlarmScanInProgress = enable
        if (!isAlarmOverlayActive.get()) return
        val view = LockWindowManager.getAlarmView() ?: return
        val wm = mAlarmActiveWindowManager ?: return
        try {
            if (view.parent != null && view.layoutParams is WindowManager.LayoutParams) {
                val params = view.layoutParams as WindowManager.LayoutParams
                if (enable) {
                    params.flags = params.flags or
                        (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                         WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    params.alpha = 0.0f
                    isAlarmOverlayPassthrough = true
                } else {
                    params.flags = params.flags and
                        (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() and
                         WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv())
                    params.alpha = 1.0f
                    isAlarmOverlayPassthrough = false
                }
                wm.updateViewLayout(view, params)
                Log.d(TAG, "setAlarmOverlayPassthrough: enable=$enable")
            }
        } catch (e: Exception) {
            Log.e(TAG, "setAlarmOverlayPassthrough failed", e)
        }
    }

    fun onAlarmScanResult(success: Boolean) {
        if (!isAlarmOverlayActive.get()) return
        showAlarmOverlayIfHidden()
        val ctx = LockWindowManager.getAlarmContext() ?: return
        if (success) {
            stopAlarmInlineScan()
            LockWindowManager.getAlarmView()?.findViewById<View>(R.id.layout_alarm_scan)?.visibility = View.GONE
            onAnyChallengePassed()
        } else {
            Toast.makeText(ctx, "未识别二维码，请再试一次", Toast.LENGTH_SHORT).show()
        }
    }

    fun detachAlarmController(controller: LockWindowManager.AlarmOverlayController) {
        if (mAlarmController === controller) {
            mAlarmController = null
        }
    }

    /**
     * 在 Activity.onResume/onNewIntent 中调用，重新关联当前 Activity 为覆盖层控制器。
     * 解决 SINGLE_TOP 下 Activity 不重建导致 mAlarmController 指向旧实例的问题。
     */
    fun reattachAlarmController(controller: LockWindowManager.AlarmOverlayController) {
        Log.d(TAG, "reattachAlarmController: active=${isAlarmOverlayActive.get()}, controller=${controller.javaClass.simpleName}")
        if (isAlarmOverlayActive.get()) {
            mAlarmController = controller
            Log.d(TAG, "reattachAlarmController: controller updated")
        } else {
            Log.w(TAG, "reattachAlarmController: overlay NOT active, controller NOT updated")
        }
    }

    private fun showAlarmActionPanel(
        layoutActions: View?,
        btnClose: Button?,
        btnSnooze: Button?,
        forceHide: Boolean = false
    ) {
        // challenge 未完成时仍允许一次“再响”：只让声音/震动静默，挑战本身继续。
        val canSnooze = forceHide && alarmRemainingSnoozeCount > 0
        layoutActions?.visibility = if (!forceHide || canSnooze) View.VISIBLE else View.GONE
        btnClose?.visibility = if (forceHide) View.GONE else View.VISIBLE
        btnClose?.isEnabled = !forceHide
        btnSnooze?.visibility = if (canSnooze) View.VISIBLE else View.GONE
        btnSnooze?.isEnabled = canSnooze
        btnSnooze?.text = if (canSnooze) "静音 ${alarmSnoozeMinutes} 分钟（挑战继续）" else "稍后再响 (0)"
    }

    private var alarmPassthroughRestoreRunnable: Runnable? = null

    internal fun requestSnoozeFromHardware(source: String = "HARDWARE"): Boolean = requestSnooze(source)

    private fun requestSnooze(source: String): Boolean {
        if (!isAlarmOverlayActive.get() || alarmId <= 0) return false
        val ctx = LockWindowManager.getAlarmContext() ?: return false
        val view = LockWindowManager.getAlarmView() ?: return false

        // 再响只属于第一关尚未完成的阶段；进入第二关或最终回顾后都不能再使用。
        if (isTwoStageUnlock && isAlarmFirstStagePassed) return false
        val btnClose = view.findViewById<Button>(R.id.btn_alarm_close)
        if (btnClose?.visibility == View.VISIBLE) return false
        if (alarmRemainingSnoozeCount <= 0 || AlarmScheduler.getRemainingSnoozeCount(ctx, alarmId) <= 0) return false
        if (!AlarmScheduler.recordSnoozeUsed(ctx, alarmId)) {
            alarmRemainingSnoozeCount = 0
            return false
        }

        alarmRemainingSnoozeCount = AlarmScheduler.getRemainingSnoozeCount(ctx, alarmId)
        val durationMs = alarmSnoozeMinutes.coerceAtLeast(1) * 60_000L
        val snoozeUntilMs = System.currentTimeMillis() + durationMs
        AlarmVibrationService.silenceForSnooze(ctx, alarmId, durationMs)
        alarmProtectionEventBridge.recordAlarmSnooze(
            alarmId = alarmId.toString(),
            snoozeIndex = AlarmScheduler.getSnoozeUsedCount(ctx, alarmId),
            snoozeUntilMs = snoozeUntilMs
        )

        showAlarmActionPanel(
            view.findViewById(R.id.layout_alarm_actions),
            btnClose,
            view.findViewById(R.id.btn_alarm_snooze),
            forceHide = true
        )
        Toast.makeText(ctx, "已静音 ${alarmSnoozeMinutes} 分钟，挑战仍可继续", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "snooze silence started: id=$alarmId source=$source minutes=$alarmSnoozeMinutes challengePreserved=true")
        return true
    }

    private fun handleAlarmAction(snooze: Boolean) {
        if (snooze) {
            requestSnooze("BUTTON")
            return
        }
        if (alarmId > 0) {
            alarmProtectionEventBridge.recordAlarmDismiss(
                alarmId = alarmId.toString(),
                method = "USER_ACTION"
            )
        }
        dismissAlarmOverlay("闹钟已关闭", false)
    }

    private fun getAlarmLayoutParams(): WindowManager.LayoutParams {
        // Use TYPE_ACCESSIBILITY_OVERLAY when the accessibility service is active —
        // it has higher z-order priority and can overlay system dialogs (power menu, etc.).
        // Check both mCoreService (set via init) and CoreService.instance (static singleton) as fallback.
        val hasCore = LockWindowManager.getAlarmCoreService() != null
        val hasInstance = CoreService.instance != null
        val isActive = CoreService.isServiceActive
        val a11yActive = (hasCore || hasInstance) && isActive
        Log.i(TAG, "getAlarmLayoutParams: hasCore=$hasCore, hasInstance=$hasInstance, isActive=$isActive, a11yActive=$a11yActive, type=${if (a11yActive) "A11Y" else "APP"}")
        return buildAlarmOverlayParams(isA11y = a11yActive)
    }

    /**
     * 扫码按钮点击处理，不依赖 mAlarmController（AlarmActivity 可能已被系统销毁）。
     * 相机会话需在 Activity 窗口中运行（TYPE_APPLICATION_OVERLAY 不支持 SurfaceView 预览），
     * 因此启动 AlarmActivity 承载扫码界面。
     */
    private fun handleAlarmScanClick() {
        val ctx = LockWindowManager.getAlarmContext() ?: return
        val hasPermission = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "handleAlarmScanClick: hasPermission=$hasPermission, controller=${mAlarmController != null}")
        LogExporter.logEvent("AlarmOverlay", "SCAN_CLICK hasPermission=$hasPermission controller=${mAlarmController != null} inline=${LockWindowManager.isCurrentOverlayInline()}")
        if (mAlarmController != null) {
            mAlarmController?.launchQrScan()
            return
        }
        if (hasPermission) {
            // [Fix: password-less lock screen] 完全移除覆盖层而非仅穿透，让用户能操作锁屏界面上滑使用相机。
            // 扫码结束后 showAlarmOverlayIfHidden() 会重新附加覆盖层。
            isAlarmScanInProgress = true
            try {
                if (LockWindowManager.getAlarmView() != null && mAlarmActiveWindowManager != null && LockWindowManager.getAlarmView()?.parent != null) {
                    // [Fix: a11y overlay crash] removeView 前清空焦点
                    LockWindowManager.getAlarmView()?.clearFocus()
                    mAlarmActiveWindowManager?.removeView(LockWindowManager.getAlarmView())
                    isAlarmOverlayAttached = false
                    Log.d(TAG, "handleAlarmScanClick: overlay removed for scan, unlocking lock screen")
                }
            } catch (e: Exception) {
                Log.w(TAG, "handleAlarmScanClick: Failed to remove alarm overlay", e)
            }
            val intent = Intent(ctx, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra("start_scan", true)
            }
            ctx.startActivity(intent)
        } else {
            // 无权限且 controller 为空：启动 AlarmActivity 来请求权限
            hideAlarmOverlayForPermission()
            val intent = Intent(ctx, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra("request_camera_permission", true)
            }
            ctx.startActivity(intent)
        }
    }

    fun startAlarmInlineScan() {
        val view = LockWindowManager.getAlarmView()
        if (view == null) {
            Log.w(TAG, "startAlarmInlineScan: mAlarmView is NULL, cannot start scan")
            return
        }
        view.findViewById<View>(R.id.layout_alarm_scan).visibility = View.VISIBLE
        // Barcode scanner auto-starts based on layout visibility
        val barcodeView = view.findViewById<com.journeyapps.barcodescanner.DecoratedBarcodeView>(R.id.barcode_alarm_scanner)
        barcodeView?.resume()
        barcodeView?.decodeContinuous { result ->
            if (result != null) {
                barcodeView.pause()
                onAlarmScanResult(true)
            }
        }
    }

    fun stopAlarmInlineScan() {
        val view = LockWindowManager.getAlarmView() ?: return
        val barcodeView = view.findViewById<com.journeyapps.barcodescanner.DecoratedBarcodeView>(R.id.barcode_alarm_scanner)
        barcodeView?.pause()
    }

    fun fallbackFromQrToMath() {
        val view = LockWindowManager.getAlarmView() ?: return
        val otherChallengesActive = (targetMathCount > 0 && currentMathIndex < targetMathCount) ||
            (targetPoemCount > 0 && currentPoemIndex < targetPoemCount) ||
            (targetWordCount > 0 && currentWordIndex < targetWordCount) ||
            (targetClozeCount > 0 && currentClozeIndex < targetClozeCount) ||
            (targetCustomCount > 0 && currentCustomIndex < targetCustomCount)

        if (!otherChallengesActive) {
            targetMathCount = 3
            currentMathIndex = 0
            val cvMath = view.findViewById<View>(R.id.cv_alarm_math)
            cvMath?.visibility = View.VISIBLE
            rollMath()
        }
    }

    fun reassertAlarmOverlayOverSystemDialog(reason: String) {
        if (!isAlarmOverlayActive.get()) return
        val view = LockWindowManager.getAlarmView() ?: return
        val wm = mAlarmActiveWindowManager ?: return
        var wasPassthrough = false
        try {
            if (view.parent != null && view.layoutParams is WindowManager.LayoutParams) {
                val params = view.layoutParams as WindowManager.LayoutParams
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()

                // 扫码穿透模式下，临时恢复触摸拦截和透明度以阻挡系统对话框
                wasPassthrough = isAlarmOverlayPassthrough
                if (wasPassthrough) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    params.alpha = 1.0f
                }

                // Upgrade to TYPE_ACCESSIBILITY_OVERLAY if the service is active —
                // it sits above system dialogs (power menu, etc.) in the z-order.
                val canUseA11y = (LockWindowManager.getAlarmCoreService() != null || CoreService.instance != null) && LockWindowManager.isServiceActive()
                if (canUseA11y && params.type != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
                    val a11yWm = (LockWindowManager.getAlarmCoreService() ?: CoreService.instance)!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val newParams = buildAlarmOverlayParams(isA11y = true)
                    newParams.flags = newParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    if (wasPassthrough) {
                        newParams.flags = newParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    }
                    // [Fix: a11y overlay crash] removeView 前清空焦点，防止 ViewRootImpl.scrollToRectOrFocus 崩溃
                    view.clearFocus()
                    wm.removeView(view)
                    a11yWm.addView(view, newParams)
                    mAlarmActiveWindowManager = a11yWm
                } else {
                    wm.updateViewLayout(view, params)
                }

                // 延迟恢复穿透模式，给予足够时间让 BACK 键关闭系统对话框
                // 如果用户持续按住电源键，恢复太快会导致关机菜单反复弹出
                if (wasPassthrough) {
                    alarmPassthroughRestoreRunnable?.let { LockWindowManager.getAlarmUiHandler().removeCallbacks(it) }
                    alarmPassthroughRestoreRunnable = Runnable {
                        alarmPassthroughRestoreRunnable = null
                        setAlarmOverlayPassthrough(true)
                    }
                    LockWindowManager.getAlarmUiHandler().postDelayed(alarmPassthroughRestoreRunnable!!, 2000L)
                }
            }
            Log.i(TAG, "reassertAlarmOverlayOverSystemDialog: reason=$reason, wasPassthrough=$wasPassthrough")
        } catch (e: Exception) {
            Log.e(TAG, "reassertAlarmOverlayOverSystemDialog failed", e)
        }
    }

    /**
     * 将内嵌模式（inline）的闹钟覆盖层切换到窗口模式（TYPE_ACCESSIBILITY_OVERLAY）。
     * 内嵌模式下 view.layoutParams 不是 WindowManager.LayoutParams，
     * 导致 reassertAlarmOverlayOverSystemDialog 的升级逻辑无法执行。
     * 在检测到系统对话框（关机菜单等）时调用此方法，使覆盖层能升至系统对话框之上。
     */
    fun ensureAlarmOverlayInWindowMode(): Boolean {
        if (!LockWindowManager.isCurrentOverlayInline()) return true
        if (!isAlarmOverlayActive.get()) return false
        val view = LockWindowManager.getAlarmView() ?: return false
        val service = LockWindowManager.getAlarmCoreService() ?: CoreService.instance ?: return false
        if (!LockWindowManager.isServiceActive()) return false
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return try {
            if (view.parent != null) {
                // [Fix: a11y overlay crash] removeView 前清空焦点
                view.clearFocus()
                (view.parent as? android.view.ViewManager)?.removeView(view)
            }
            val params = buildAlarmOverlayParams(isA11y = true)
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            wm.addView(view, params)
            mAlarmActiveWindowManager = wm
            isAlarmOverlayAttached = true
            Log.i(TAG, "ensureAlarmOverlayInWindowMode: switched from inline to window mode (TYPE_ACCESSIBILITY_OVERLAY)")
            LogExporter.logEvent("AlarmOverlay", "SWITCHED_TO_WINDOW_MODE")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ensureAlarmOverlayInWindowMode failed", e)
            LogExporter.logEvent("AlarmOverlay", "SWITCH_TO_WINDOW_MODE_FAILED err=${e.javaClass.simpleName}")
            false
        }
    }

    private fun buildAlarmOverlayParams(isA11y: Boolean): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            format = PixelFormat.TRANSLUCENT
            type = if (isA11y) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            // 清除 FLAG_NOT_FOCUSABLE，FLAG_DISMISS_KEYGUARD 需要窗口可获取焦点
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            // [DO NOT DELETE] 无 FLAG_LAYOUT_NO_LIMITS，确保键盘弹出时窗口能被 resize
            // 关联：setupKeyboardAwareScroll() 依赖此 resize 生效
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // 键盘弹出时自动上移，避免挡住单词拼写输入框
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            alpha = 1.0f
        }
    }

    private fun dismissAlarmOverlay(reason: String, snooze: Boolean) {
        if (!isAlarmOverlayActive.get()) return
        val ctx = LockWindowManager.getAlarmContext() ?: return

        stopAlarmIntensityUpdate()

        if (snooze && alarmId > 0) {
            AlarmScheduler.scheduleSnooze(ctx, alarmId, alarmSnoozeMinutes)
        }

        try {
            // [内嵌模式] View 在 Activity 的 contentView 中，不通过 WindowManager 管理
            if (!LockWindowManager.isCurrentOverlayInline() && LockWindowManager.getAlarmView() != null && mAlarmActiveWindowManager != null && LockWindowManager.getAlarmView()?.parent != null) {
                // [Fix: a11y overlay crash] removeView 前清空焦点
                LockWindowManager.getAlarmView()?.clearFocus()
                mAlarmActiveWindowManager?.removeView(LockWindowManager.getAlarmView())
            }
        } catch (_: Exception) {
        } finally {
            mAlarmActiveWindowManager = null
            LockWindowManager.setAlarmView(null)
            isAlarmOverlayAttached = false
            isAlarmOverlayActive.set(false)
            Log.i(TAG, "dismissAlarmOverlay: clearing active flag, reason=$reason")
            AlarmScheduler.clearAlarmActive(ctx)
            LogExporter.logEvent("AlarmRecovery", "clearAlarmActive (reason=$reason)")
            AlarmVibrationService.stop(ctx)
            Toast.makeText(ctx, reason, Toast.LENGTH_SHORT).show()
            mAlarmController?.onAlarmOverlayFinished()

            stageCooldownHandler?.removeCallbacksAndMessages(null)
            stageCooldownHandler = null
            stageCooldownRemainingSeconds = 0
            singleStageCooldownUntilMs = 0L

            // [Fix: anti-uninstall gap] 闹钟关闭后，若存在活跃锁屏且无障碍仍开启，
            // 显示初始化覆盖层桥接重连空窗期（防止用户在此期间卸载）。
            // 条件：
            //   1. 非 snooze（snooze 时闹钟会重新响起，无需桥接）
            //   2. 有活跃锁屏（isLocked）—— 没有锁屏则没有需要保护的窗口
            //   3. 无障碍服务当前活跃 —— 无障碍已关闭则没有保护可丢失，无需兜底
            val warmupStable = CoreService.antiUninstallWarmupStable
            if (!snooze && LockWindowManager.isLocked && LockWindowManager.isServiceActive() && !warmupStable) {
                Log.i(TAG, "dismissAlarmOverlay: showing init overlay to bridge anti-uninstall gap")
                LockWindowManager.showSystemInitOverlay("alarm_dismissed", durationMs = 30000L)
            }
        }
    }
}
