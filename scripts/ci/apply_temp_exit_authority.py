from pathlib import Path
import re


def sub_once(text: str, pattern: str, replacement: str, label: str) -> str:
    out, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"[{label}] expected exactly one match, got {count}")
    return out


lock_path = Path("app/src/main/java/com/zhizhi/app/ui/LockWindowManager.kt")
monitor_path = Path("app/src/main/java/com/zhizhi/app/service/MonitorService.kt")
lock = lock_path.read_text(encoding="utf-8")

if "startTemporaryAccess(emergency): force removeView ignored" in lock:
    lock = sub_once(
        lock,
        r'            // 移除锁屏视图\n            try \{.*?            clearSystemDialogCompatState\(\)\n            return',
        '''            dismissLockScreen(LockDismissAuthority.TEMPORARY_ACCESS)
            return''',
        "emergency temporary-access exit authority",
    )

if "startTemporaryAccess: force removeView ignored" in lock:
    lock = sub_once(
        lock,
        r'        // 🛡️ \[Anti-Zombie\] 直接强制移除锁屏视图，不依赖 isLocked 标志位\n        // 因为 startTemporaryAccess 已经扣了额度、设了冷却，锁屏必须消失\n        try \{.*?        clearSystemDialogCompatState\(\)',
        '        dismissLockScreen(LockDismissAuthority.TEMPORARY_ACCESS)',
        "temporary-access exit authority",
    )

# Keep the authority API formatting readable after the one-time migration.
lock = lock.replace("\nval now = System.currentTimeMillis()", "\n        val now = System.currentTimeMillis()", 1)
lock_path.write_text(lock, encoding="utf-8", newline="\n")

monitor = monitor_path.read_text(encoding="utf-8")
monitor = monitor.replace(
    "/** 小窗关闭后解除锁机（冷却期内不解锁，冷却结束前仍保持锁屏） */",
    "/** 小窗 Presence 消失后仅清理检测状态；锁屏只能由四个显式出口撤销。 */",
    1,
)
monitor_path.write_text(monitor, encoding="utf-8", newline="\n")

if "startTemporaryAccess: force removeView ignored" in lock or "startTemporaryAccess(emergency): force removeView ignored" in lock:
    raise RuntimeError("temporary access still bypasses LockDismissAuthority")
if "LockDismissAuthority.TEMPORARY_ACCESS" not in lock:
    raise RuntimeError("temporary access authority call was not created")

print("Temporary-access exit authority patch applied successfully.")
