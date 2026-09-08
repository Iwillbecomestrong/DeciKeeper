from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
scheduler = (ROOT / "app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt").read_text(encoding="utf-8")

required = [
    "stage2UseIndependentAlertPolicy",
    "stage2AlarmMode",
    "stage2VibrationMode",
    "stage2VibrationIntensity",
    "stage2RingVolume",
    'obj.put("stage2UseIndependentAlertPolicy"',
    'obj.put("stage2AlarmMode"',
    'obj.put("stage2VibrationMode"',
    'obj.put("stage2VibrationIntensity"',
    'obj.put("stage2RingVolume"',
    'obj.optBoolean("stage2UseIndependentAlertPolicy", false)',
]

missing = [needle for needle in required if needle not in scheduler]
if missing:
    print("Stage-two alert policy persistence contract failures:")
    for item in missing:
        print(f" - missing: {item}")
    raise SystemExit(1)

print("Stage-two alert policy persistence contract passed")
