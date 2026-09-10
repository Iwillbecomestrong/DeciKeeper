from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
service = (ROOT / "app/src/main/java/com/decikeeper/app/service/AlarmVibrationService.kt").read_text(encoding="utf-8")

required = [
    "AlarmAlertPolicy",
    "StageAlertPolicy",
    "alertCycleStartTime",
    "ACTION_START_STAGE2_CYCLE",
    "fun startStage2AlertCycle(",
    "fun resetAlertCycle(",
    "AlarmAlertPolicy.vibrationLevel",
    "AlarmAlertPolicy.ringLevel",
]

failures = [f"missing: {needle}" for needle in required if needle not in service]

legacy = [
    "elapsedSec / 10",
    "(elapsedSec - 300) / 10",
    "if (autoEscalate",
]
for needle in legacy:
    if needle in service:
        failures.append(f"legacy behavior still active: {needle}")

snooze_block = service.split("private fun applySnoozeSilence", 1)[1].split("// Event-driven logging bridge", 1)[0]
if "resetAlertCycle" not in snooze_block:
    failures.append("snooze expiry does not reset alert cycle")

if failures:
    print("Alarm alert cycle contract failures:")
    for item in failures:
        print(f" - {item}")
    raise SystemExit(1)

print("Alarm alert cycle contract passed")
