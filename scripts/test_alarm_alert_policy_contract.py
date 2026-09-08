from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
policy_path = ROOT / "app/src/main/java/com/zhizhi/app/manager/AlarmAlertPolicy.kt"
service_path = ROOT / "app/src/main/java/com/zhizhi/app/service/AlarmVibrationService.kt"
fragment_path = ROOT / "app/src/main/java/com/zhizhi/app/ui/AlarmFragment.kt"

failures = []

if not policy_path.exists():
    failures.append("AlarmAlertPolicy.kt is missing")
else:
    policy = policy_path.read_text(encoding="utf-8")
    for needle, label in [
        ("PROGRESSIVE_STEP_MS = 30_000L", "30-second progressive step"),
        ("fun progressiveLevel", "progressive level seam"),
        ("fun resolveStagePolicy", "stage-two inheritance seam"),
    ]:
        if needle not in policy:
            failures.append(f"missing {label}")

service = service_path.read_text(encoding="utf-8")
if "AlarmAlertPolicy.progressiveLevel" not in service:
    failures.append("service does not use unified progressive level")
if "elapsedSec / 10" in service or "(elapsedSec - 300) / 10" in service:
    failures.append("legacy 10-second progressive ramp is still active")
if "if (autoEscalate" in service:
    failures.append("legacy five-minute autoEscalate behavior is still active")

fragment = fragment_path.read_text(encoding="utf-8")
if "5分钟后自动升级到最大" in fragment or "5分钟后突破最大值继续渐进至10" in fragment:
    failures.append("legacy five-minute escalation UI is still exposed")

if failures:
    print("Alarm alert policy contract failures:")
    for item in failures:
        print(f" - {item}")
    raise SystemExit(1)

print("Alarm alert policy contract passed")
