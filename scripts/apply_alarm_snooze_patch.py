from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def load(path: str) -> tuple[Path, str]:
    p = ROOT / path
    return p, p.read_text(encoding="utf-8")

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)

# -----------------------------------------------------------------------------
# LockAlarmOverlay.kt
# -----------------------------------------------------------------------------
p, s = load("app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt")
s = replace_once(
    s,
    "        this.alarmRemainingSnoozeCount = AlarmScheduler.getRemainingSnoozeCount(context, alarmId)\n",
    "        // 再响现在是每次真实闹钟 session 仅一次：只暂停声音/震动，不关闭挑战。\n"
    "        this.alarmRemainingSnoozeCount = if (alarmId > 0 && AlarmScheduler.getSnoozeUsedCount(context, alarmId) == 0) 1 else 0\n",
    "one-shot snooze initialization",
)
old_panel = '''    private fun showAlarmActionPanel(
        layoutActions: View?,
        btnClose: Button?,
        btnSnooze: Button?,
        forceHide: Boolean = false
    ) {
        layoutActions?.visibility = if (forceHide) View.GONE else View.VISIBLE
        btnClose?.isEnabled = !forceHide
        btnSnooze?.isEnabled = !forceHide
        btnSnooze?.text = if (forceHide) "..." else "暂停 ${alarmSnoozeMinutes} 分钟"
    }
'''
new_panel = '''    private fun showAlarmActionPanel(
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
'''
s = replace_once(s, old_panel, new_panel, "action panel")
old_action = '''    private fun handleAlarmAction(snooze: Boolean) {
        if (snooze && alarmId > 0) {
            val ctx = LockWindowManager.getAlarmContext() ?: return
            val usedBefore = AlarmScheduler.getSnoozeUsedCount(ctx, alarmId)
            AlarmScheduler.scheduleSnooze(ctx, alarmId, alarmSnoozeMinutes)
            val usedAfter = AlarmScheduler.getSnoozeUsedCount(ctx, alarmId)
            if (usedAfter > usedBefore) {
                val snoozeIndex = usedAfter
                val snoozeUntilMs = System.currentTimeMillis() + alarmSnoozeMinutes * 60_000L
                alarmProtectionEventBridge.recordAlarmSnooze(
                    alarmId = alarmId.toString(),
                    snoozeIndex = snoozeIndex,
                    snoozeUntilMs = snoozeUntilMs
                )
            }
            dismissAlarmOverlay("已暂停 ${alarmSnoozeMinutes} 分钟", false)
        } else {
            if (alarmId > 0) {
                alarmProtectionEventBridge.recordAlarmDismiss(
                    alarmId = alarmId.toString(),
                    method = "USER_ACTION"
                )
            }
            dismissAlarmOverlay("闹钟已关闭", false)
        }
    }
'''
new_action = '''    internal fun requestSnoozeFromHardware(source: String = "HARDWARE"): Boolean = requestSnooze(source)

    private fun requestSnooze(source: String): Boolean {
        if (!isAlarmOverlayActive.get() || alarmId <= 0) return false
        val ctx = LockWindowManager.getAlarmContext() ?: return false
        val view = LockWindowManager.getAlarmView() ?: return false

        // 再响只属于第一关尚未完成的阶段；进入第二关或最终回顾后都不能再使用。
        if (isTwoStageUnlock && isAlarmFirstStagePassed) return false
        val btnClose = view.findViewById<Button>(R.id.btn_alarm_close)
        if (btnClose?.visibility == View.VISIBLE) return false
        if (alarmRemainingSnoozeCount <= 0 || AlarmScheduler.getSnoozeUsedCount(ctx, alarmId) > 0) return false
        if (!AlarmScheduler.recordSnoozeUsed(ctx, alarmId)) {
            alarmRemainingSnoozeCount = 0
            return false
        }

        alarmRemainingSnoozeCount = 0
        val durationMs = alarmSnoozeMinutes.coerceAtLeast(1) * 60_000L
        val snoozeUntilMs = System.currentTimeMillis() + durationMs
        AlarmVibrationService.silenceForSnooze(ctx, alarmId, durationMs)
        alarmProtectionEventBridge.recordAlarmSnooze(
            alarmId = alarmId.toString(),
            snoozeIndex = 1,
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
'''
s = replace_once(s, old_action, new_action, "snooze action")
# Completing the final challenge during a silent snooze must cancel the pending stage-1 restore.
needle = '''        // All done (single-stage, or two-stage all challenges complete)
        view.clearFocus()
'''
replacement = '''        // All done (single-stage, or two-stage all challenges complete).
        // If stage 1 was completed while a silent snooze timer was active, attenuate() cancels
        // that restore timer so the old stage-1 alarm cannot suddenly resume during review.
        AlarmVibrationService.attenuate(ctx)
        view.clearFocus()
'''
s = replace_once(s, needle, replacement, "final challenge snooze cancellation")
p.write_text(s, encoding="utf-8")

# -----------------------------------------------------------------------------
# AlarmVibrationService.kt
# -----------------------------------------------------------------------------
p, s = load("app/src/main/java/com/zhizhi/app/service/AlarmVibrationService.kt")
s = replace_once(
    s,
    "import com.zhizhi.app.ui.AlarmActivity\nimport com.zhizhi.app.ui.LockWindowManager\n",
    "import com.zhizhi.app.ui.AlarmActivity\nimport com.zhizhi.app.ui.LockAlarmOverlayController\nimport com.zhizhi.app.ui.LockWindowManager\n",
    "overlay controller import",
)
old_companion = '''        const val ACTION_ATTENUATE = "com.zhizhi.app.service.ACTION_ATTENUATE"

        @JvmStatic
        fun attenuate(context: Context) {
'''
new_companion = '''        const val ACTION_ATTENUATE = "com.zhizhi.app.service.ACTION_ATTENUATE"
        const val ACTION_SNOOZE_SILENCE = "com.zhizhi.app.service.ACTION_SNOOZE_SILENCE"
        private const val EXTRA_SNOOZE_DURATION_MS = "alarm_snooze_duration_ms"

        @JvmStatic
        fun silenceForSnooze(context: Context, alarmId: Int, durationMs: Long) {
            if (alarmId <= 0 || durationMs <= 0L) return
            try {
                val intent = Intent(context, AlarmVibrationService::class.java).apply {
                    action = ACTION_SNOOZE_SILENCE
                    putExtra(EXTRA_ALARM_ID, alarmId)
                    putExtra(EXTRA_SNOOZE_DURATION_MS, durationMs)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to silence alarm for snooze: ${e.message}")
            }
        }

        @JvmStatic
        fun attenuate(context: Context) {
'''
s = replace_once(s, old_companion, new_companion, "silent snooze companion API")
s = replace_once(
    s,
    "    private var mediaPlayer: android.media.MediaPlayer? = null\n    private var isAttenuated: Boolean = false\n",
    "    private var mediaPlayer: android.media.MediaPlayer? = null\n"
    "    private var isAttenuated: Boolean = false\n"
    "    @Volatile private var isSnoozeSilenced: Boolean = false\n"
    "    private var snoozeRestoreRunnable: Runnable? = null\n",
    "silent snooze fields",
)
insert_after_attenuation = '''    private fun applyAttenuation() {
        isAttenuated = true
        currentDisplayVolume = 1
        currentDisplayIntensity = 1
        currentVibrationMode = "attenuated"
        val mpVol = 0.02f
        mediaPlayer?.setVolume(mpVol, mpVol)

        if (alarmMode != AlarmScheduler.AlarmMode.RING_ONLY) {
            if (vibrator == null) {
                vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator?.cancel()
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()
            val pattern = longArrayOf(0, 300, 2700)
            val amplitudes = intArrayOf(0, 20, 0)
            val effect = VibrationEffect.createWaveform(pattern, amplitudes, 0)
            vibrator?.vibrate(effect, audioAttributes)
        }
        Log.i(TAG, "Alarm attenuated: volume=0.02f, vibration=mild 20/255 pulse")
    }
'''
replacement_attenuation = insert_after_attenuation + '''
    private fun applySnoozeSilence(targetAlarmId: Int, durationMs: Long) {
        if (targetAlarmId <= 0 || targetAlarmId != alarmId || durationMs <= 0L) return
        snoozeRestoreRunnable?.let { handler.removeCallbacks(it) }
        isSnoozeSilenced = true
        currentDisplayVolume = 0
        currentDisplayIntensity = 0
        currentVibrationMode = "snooze_silent"
        mediaPlayer?.setVolume(0f, 0f)
        vibrator?.cancel()

        snoozeRestoreRunnable = Runnable {
            snoozeRestoreRunnable = null
            val sameSession = AlarmScheduler.isAlarmActive(this) && AlarmScheduler.getActiveAlarmId(this) == targetAlarmId
            val stillStageOne = !AlarmScheduler.isFirstStagePassed(this)
            if (isSnoozeSilenced && sameSession && stillStageOne) {
                isSnoozeSilenced = false
                Log.i(TAG, "Silent snooze expired; restoring alarm output for id=$targetAlarmId")
                startVibrationImpl()
            } else {
                isSnoozeSilenced = false
                Log.i(TAG, "Silent snooze restore skipped: sameSession=$sameSession stillStageOne=$stillStageOne")
            }
        }
        handler.postDelayed(snoozeRestoreRunnable!!, durationMs)
        Log.i(TAG, "Alarm output silenced for snooze: id=$targetAlarmId durationMs=$durationMs challengePreserved=true")
    }
'''
s = replace_once(s, insert_after_attenuation, replacement_attenuation, "apply silent snooze")
old_screenoff = '''                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen off detected, scheduling vibration retries")
                    handler.postDelayed({ startVibrationImpl() }, 300)
                    handler.postDelayed({ startVibrationImpl() }, 800)
                    handler.postDelayed({ startVibrationImpl() }, 1500)
                }
'''
new_screenoff = '''                Intent.ACTION_SCREEN_OFF -> {
                    // AlarmActivity keeps the screen on while the challenge is active, so a SCREEN_OFF here
                    // is our best app-visible signal for a short power-key press. Use the same one-shot snooze
                    // path as the UI/volume keys. If it cannot be consumed, keep the original OEM recovery.
                    val snoozed = LockAlarmOverlayController.requestSnoozeFromHardware("POWER_KEY")
                    if (snoozed) {
                        Log.i(TAG, "Screen off consumed as one-shot silent snooze")
                    } else {
                        Log.d(TAG, "Screen off detected, scheduling vibration retries")
                        handler.postDelayed({ startVibrationImpl() }, 300)
                        handler.postDelayed({ startVibrationImpl() }, 800)
                        handler.postDelayed({ startVibrationImpl() }, 1500)
                    }
                }
'''
s = replace_once(s, old_screenoff, new_screenoff, "power key snooze")
s = replace_once(
    s,
    '''            if (vibrator != null) {
                Log.d(TAG, "Vibration keep-alive: re-triggering")
                startVibrationImpl()
            }
''',
    '''            if (!isSnoozeSilenced && vibrator != null) {
                Log.d(TAG, "Vibration keep-alive: re-triggering")
                startVibrationImpl()
            }
''',
    "keepalive silence guard",
)
old_actions = '''    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ATTENUATE) {
            Log.i(TAG, "Received ACTION_ATTENUATE in onStartCommand")
            applyAttenuation()
            return START_STICKY
        }
        val newAlarmId = intent?.getIntExtra(EXTRA_ALARM_ID, -1) ?: -1
'''
new_actions = '''    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SNOOZE_SILENCE) {
            val targetAlarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
            val durationMs = intent.getLongExtra(EXTRA_SNOOZE_DURATION_MS, 0L)
            applySnoozeSilence(targetAlarmId, durationMs)
            return START_STICKY
        }
        if (intent?.action == ACTION_ATTENUATE) {
            Log.i(TAG, "Received ACTION_ATTENUATE in onStartCommand")
            snoozeRestoreRunnable?.let { handler.removeCallbacks(it) }
            snoozeRestoreRunnable = null
            isSnoozeSilenced = false
            applyAttenuation()
            return START_STICKY
        }
        val newAlarmId = intent?.getIntExtra(EXTRA_ALARM_ID, -1) ?: -1
'''
s = replace_once(s, old_actions, new_actions, "service actions")
s = replace_once(
    s,
    "        handler.removeCallbacks(vibrationKeepAliveRunnable)\n        handler.removeCallbacks(ringDisplayUpdateRunnable)\n",
    "        handler.removeCallbacks(vibrationKeepAliveRunnable)\n"
    "        handler.removeCallbacks(ringDisplayUpdateRunnable)\n"
    "        snoozeRestoreRunnable?.let { handler.removeCallbacks(it) }\n"
    "        snoozeRestoreRunnable = null\n",
    "destroy snooze cleanup",
)
s = replace_once(
    s,
    '''    @SuppressLint("MissingPermission")
    private fun startVibrationImpl() {
        if (isAttenuated) {
''',
    '''    @SuppressLint("MissingPermission")
    private fun startVibrationImpl() {
        if (isSnoozeSilenced) {
            currentDisplayVolume = 0
            currentDisplayIntensity = 0
            mediaPlayer?.setVolume(0f, 0f)
            vibrator?.cancel()
            return
        }
        if (isAttenuated) {
''',
    "normal output silence guard",
)
s = replace_once(
    s,
    '''    private fun updateRingVolumeDisplay(mode: AlarmScheduler.VibrationMode, elapsedSec: Int, stagePassed: Boolean) {
        if (isAttenuated) {
''',
    '''    private fun updateRingVolumeDisplay(mode: AlarmScheduler.VibrationMode, elapsedSec: Int, stagePassed: Boolean) {
        if (isSnoozeSilenced) {
            currentDisplayVolume = 0
            mediaPlayer?.setVolume(0f, 0f)
            return
        }
        if (isAttenuated) {
''',
    "ring display silence guard",
)
p.write_text(s, encoding="utf-8")

# -----------------------------------------------------------------------------
# AlarmActivity.kt: volume keys trigger the same one-shot silent snooze.
# -----------------------------------------------------------------------------
p, s = load("app/src/main/java/com/zhizhi/app/ui/AlarmActivity.kt")
old_key = '''    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
'''
new_key = '''    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            if (LockAlarmOverlayController.requestSnoozeFromHardware("VOLUME_KEY")) return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
'''
s = replace_once(s, old_key, new_key, "volume-key snooze")
p.write_text(s, encoding="utf-8")

# -----------------------------------------------------------------------------
# AlarmFragment.kt: keep the existing re-ring setting, but make it interval-only
# and explain the new one-shot silent semantics.
# -----------------------------------------------------------------------------
p, s = load("app/src/main/java/com/zhizhi/app/ui/AlarmFragment.kt")
old_defaults = '''        // --- 3. 再响设置 ---
        addSectionLabel("再响设置")
        val (etSnoozeMinutes, etSnoozeMaxCount) = addTwoFields(
            "间隔", AlarmScheduler.getSnoozeMinutes(ctx).toString(), "分钟",
            "最多", AlarmScheduler.getMaxSnoozeCount(ctx).toString(), "次"
        )
'''
new_defaults = '''        // --- 3. 再响设置 ---
        addSectionLabel("再响设置")
        val (etSnoozeMinutes, _) = addTwoFields(
            "静音", AlarmScheduler.getSnoozeMinutes(ctx).toString(), "分钟",
            null, null, null
        )
        container.addView(TextView(ctx).apply {
            text = "本次闹钟仅可使用 1 次；仅暂停声音和震动，挑战继续"
            textSize = 12f
            setTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(12) }
        })
'''
s = replace_once(s, old_defaults, new_defaults, "default re-ring settings")
old_save = '''                // 保存再响
                val snoozeMin = etSnoozeMinutes.text.toString().toIntOrNull() ?: 10
                val snoozeMax = etSnoozeMaxCount?.text.toString().toIntOrNull() ?: 2
                if (snoozeMin > 0) AlarmScheduler.setSnoozeMinutes(ctx, snoozeMin)
                if (snoozeMax >= 0) AlarmScheduler.setMaxSnoozeCount(ctx, snoozeMax)
'''
new_save = '''                // 保存再响：每次真实闹钟固定仅 1 次；这里只配置静音时长。
                val snoozeMin = etSnoozeMinutes.text.toString().toIntOrNull() ?: 10
                if (snoozeMin > 0) AlarmScheduler.setSnoozeMinutes(ctx, snoozeMin)
                AlarmScheduler.setMaxSnoozeCount(ctx, 1)
'''
s = replace_once(s, old_save, new_save, "save one-shot re-ring settings")
p.write_text(s, encoding="utf-8")

print("Alarm snooze production patch applied")
