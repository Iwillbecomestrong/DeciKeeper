from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")

overlay = read("app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt")
service = read("app/src/main/java/com/zhizhi/app/service/AlarmVibrationService.kt")
activity = read("app/src/main/java/com/zhizhi/app/ui/AlarmActivity.kt")
fragment = read("app/src/main/java/com/zhizhi/app/ui/AlarmFragment.kt")

handle = overlay.split("private fun handleAlarmAction(snooze: Boolean)", 1)[1].split("private fun getAlarmLayoutParams()", 1)[0]
panel = overlay.split("private fun showAlarmActionPanel(", 1)[1].split("private var alarmPassthroughRestoreRunnable", 1)[0]
defaults = fragment.split("// --- 3. 再响设置 ---", 1)[1].split("// --- 4.", 1)[0]

checks = [
    ("record existing one-shot snooze usage", "AlarmScheduler.recordSnoozeUsed" in handle),
    ("silence current output instead of replacing session", "AlarmVibrationService.silenceForSnooze" in handle),
    ("do not schedule a replacement snooze alarm", "AlarmScheduler.scheduleSnooze" not in handle),
    ("do not dismiss challenge overlay on snooze", 'dismissAlarmOverlay("已暂停' not in handle),
    ("active challenge row exposes snooze", "val canSnooze = forceHide && alarmRemainingSnoozeCount > 0" in panel),
    ("unfinished challenge hides close", "btnClose?.visibility = if (forceHide) View.GONE else View.VISIBLE" in panel),
    ("unfinished challenge keeps snooze visible", "btnSnooze?.visibility = if (canSnooze) View.VISIBLE else View.GONE" in panel),
    ("runtime is capped to one snooze", "AlarmScheduler.getSnoozeUsedCount(context, alarmId) == 0" in overlay),
    ("successful snooze consumes last allowance", "alarmRemainingSnoozeCount = 0" in overlay),
    ("service has silent snooze action", "ACTION_SNOOZE_SILENCE" in service),
    ("service exposes silent snooze API", "fun silenceForSnooze(" in service),
    ("silent snooze state exists", "isSnoozeSilenced = true" in service),
    ("silent snooze sets displayed volume zero", "currentDisplayVolume = 0" in service),
    ("silent snooze sets displayed vibration zero", "currentDisplayIntensity = 0" in service),
    ("silent snooze mutes media player", "mediaPlayer?.setVolume(0f, 0f)" in service),
    ("silent snooze cancels vibrator", "vibrator?.cancel()" in service),
    ("keepalive respects silent window", "if (!isSnoozeSilenced && vibrator != null)" in service),
    ("normal vibration entry respects silent window", "if (isSnoozeSilenced)" in service),
    ("silent window schedules restoration", "snoozeRestoreRunnable" in service and "startVibrationImpl()" in service),
    ("volume key uses same snooze path", "KeyEvent.KEYCODE_VOLUME_UP" in activity and "KeyEvent.KEYCODE_VOLUME_DOWN" in activity and "LockAlarmOverlayController.requestSnoozeFromHardware" in activity),
    ("power/screen-off uses same snooze path", "Intent.ACTION_SCREEN_OFF" in service and "LockAlarmOverlayController.requestSnoozeFromHardware" in service),
    ("default settings explain one-shot behavior", "本次闹钟仅可使用 1 次" in defaults),
    ("default settings no longer expose repeat count", "getMaxSnoozeCount" not in defaults and '"最多"' not in defaults),
]

failed = [name for name, ok in checks if not ok]
if failed:
    print("Alarm snooze contract failures:")
    for item in failed:
        print(f" - {item}")
    raise SystemExit(1)

print(f"Alarm snooze contract passed: {len(checks)} checks")
