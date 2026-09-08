from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
overlay = (ROOT / "app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt").read_text(encoding="utf-8")

required = [
    ("stage 2 transition triggers alert cycle restart", "AlarmVibrationService.startStage2AlertCycle" in overlay),
    ("immediate stage 2 triggers alert cycle restart", overlay.count("AlarmVibrationService.startStage2AlertCycle") >= 2),
]

failed = [name for name, ok in required if not ok]
if failed:
    print("Stage 2 transition contract failures:")
    for item in failed:
        print(f" - {item}")
    raise SystemExit(1)

print("Stage 2 transition contract passed")
