from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
policy_path = ROOT / "app/src/main/java/com/decikeeper/app/manager/AlarmAlertPolicy.kt"

failures = []

if not policy_path.exists():
    failures.append("AlarmAlertPolicy.kt is missing")
else:
    policy = policy_path.read_text(encoding="utf-8")
    for needle, label in [
        ("PROGRESSIVE_STEP_MS = 30_000L", "30-second progressive step"),
        ("fun progressiveLevel", "progressive level seam"),
        ("fun resolveStagePolicy", "stage-two inheritance seam"),
        ("fun vibrationLevel", "vibration level resolver"),
        ("fun ringLevel", "ring level resolver"),
    ]:
        if needle not in policy:
            failures.append(f"missing {label}")

if failures:
    print("Alarm alert policy contract failures:")
    for item in failures:
        print(f" - {item}")
    raise SystemExit(1)

print("Alarm alert policy contract passed")
