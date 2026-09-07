from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"[{label}] expected exactly one literal match, got {count}")
    return text.replace(old, new, 1)


def sub_once(text: str, pattern: str, replacement: str, label: str) -> str:
    out, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"[{label}] expected exactly one regex match, got {count}")
    return out


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="\n")


lock_path = Path("app/src/main/java/com/zhizhi/app/ui/LockWindowManager.kt")
monitor_path = Path("app/src/main/java/com/zhizhi/app/service/MonitorService.kt")
core_path = Path("app/src/main/java/com/zhizhi/app/service/CoreService.kt")

# LockWindowManager: only explicit user exits may destroy a visible blacklist overlay.
lock = lock_path.read_text(encoding="utf-8")
if "fun dismissLockScreen(authority: LockDismissAuthority)" not in lock:
    lock = replace_once(
        lock,
        "fun dismissLockScreen(force: Boolean = false) {",
        "fun dismissLockScreen(authority: LockDismissAuthority) {",
        "dismiss signature",
    )
    lock = sub_once(
        lock,
        r'\s*if \(isBlacklistLock && !force\) \{\s*Log\.d\(TAG, "Dismiss rejected: Blacklist Lock Active \(use force=true or button click\)"\)\s*return\s*\}\s*',
        '\n        Log.i(TAG, "[LOCK_EXIT] authority=$authority pkg=$lastBlockedPackageName")\n\n',
        "remove force bypass guard",
    )
    lock = replace_once(
        lock,
        "dismissLockScreen(true)",
        "dismissLockScreen(LockDismissAuthority.RETURN_HOME)",
        "return-home authority",
    )
    lock = sub_once(
        lock,
        r'(resetConsecutiveNormalLockState\("free_time_granted_gap"\)\s*totalSegmentBlacklistHits = 0\s*)dismissLockScreen\(force = true\)',
        r'\1dismissLockScreen(LockDismissAuthority.REST)',
        "gap-rest authority",
    )
    lock = sub_once(
        lock,
        r'(resetConsecutiveNormalLockState\("weekly_privilege_granted"\)\s*totalSegmentBlacklistHits = 0\s*)dismissLockScreen\(force = true\)',
        r'\1dismissLockScreen(LockDismissAuthority.WEEKLY_PRIVILEGE)',
        "weekly-privilege authority",
    )
    lock = sub_once(
        lock,
        r'(resetConsecutiveNormalLockState\("free_time_granted"\)\s*totalSegmentBlacklistHits = 0\s*)dismissLockScreen\(force = true\)',
        r'\1dismissLockScreen(LockDismissAuthority.REST)',
        "rest authority",
    )
    lock = sub_once(
        lock,
        r'LogExporter\.i\("LockScreen", "\[AUTO_RELEASE\] restrictions cleared for pkg=\$targetPackage"\)\s*dismissLockScreen\(force = true\)\s*return true',
        'LogExporter.i("LockScreen", "[AUTO_RELEASE_BLOCKED] restrictions cleared for pkg=$targetPackage but visible lock requires explicit exit")\n        return false',
        "block automatic overlay release",
    )
    lock = sub_once(
        lock,
        r'(endTemporaryAccessLifecycle\(nowMs, reason = "FREE_TIME_STARTED", nextState = "FREE_TIME"\)\s*clearTemporaryAccessState\(\)\s*)dismissLockScreen\(force = true\)',
        r'\1LogExporter.i("TemporaryAccess", "[NATURAL_FREEDOM] visible lock preserved; explicit exit required")',
        "natural-freedom overlay preserve",
    )
    lock = sub_once(
        lock,
        r'internal fun shouldDismissForScreenOff\(\s*isAlarmOverlayActive: Boolean,\s*isLocked: Boolean,\s*isBlacklistLock: Boolean\s*\): Boolean \{.*?\n    \}',
        '''internal fun shouldDismissForScreenOff(
        isAlarmOverlayActive: Boolean,
        isLocked: Boolean,
        isBlacklistLock: Boolean
    ): Boolean {
        // Screen-off is not one of the four explicit exits. Preserve every visible lock.
        return false
    }''',
        "screen-off policy",
    )
    lock = replace_once(
        lock,
        'resetLockState("ScreenOff")',
        'LogExporter.logEvent("LockScreen", "[SCREEN_OFF_PRESERVE] visible lock kept; explicit exit required")',
        "remove screen-off reset",
    )
    if "dismissLockScreen(force = true)" in lock or "dismissLockScreen(true)" in lock:
        raise RuntimeError("[LockWindowManager] legacy force-dismiss call remains after patch")
    write(lock_path, lock)
else:
    print("LockWindowManager authority patch already applied.")

# MonitorService: Presence cleanup and rule changes may update detection/session state, never unlock.
monitor = monitor_path.read_text(encoding="utf-8")
if "LockWindowManager.dismissLockScreen" in monitor:
    monitor = sub_once(
        monitor,
        r'    private fun handlePresenceUnlock\(pkg: String, reason: String\) \{.*?(?=\n    /\*\* 重置检测状态 \*/)',
        '''    private fun handlePresenceUnlock(pkg: String, reason: String) {
        val lockedFor = (System.currentTimeMillis() - mPresentLockedAtMs).coerceAtLeast(0L)
        Log.i(TAG, "[Presence] CLEAR_ONLY: pkg=$pkg reason=$reason lockedFor=${lockedFor}ms")
        LogExporter.logEvent("MonitorService", "[Presence_CLEAR_ONLY] pkg=$pkg reason=$reason lockedFor=${lockedFor}ms")
        mPresentLockedPkg = null
        mPresentLockedAtMs = 0L
        mPresenceStatUnlocked++
    }
''',
        "presence clear-only",
    )
    monitor = sub_once(
        monitor,
        r'if \(decision\.dismissBlacklistOverlay\) \{\s*LockWindowManager\.dismissLockScreen\(force = true\)\s*currentLockedPkg = null\s*\}',
        '''if (decision.dismissBlacklistOverlay) {
            Log.w(TAG, "[AUTO_RELEASE_BLOCKED] visible lock requires explicit exit")
        }''',
        "monitor automatic release",
    )
    if "LockWindowManager.dismissLockScreen" in monitor:
        raise RuntimeError("[MonitorService] detection path still directly dismisses the lock")
    write(monitor_path, monitor)
else:
    print("MonitorService dismiss-authority patch already applied.")

# CoreService: Accessibility reconnect actively resubmits a stale blacklist overlay for rebuild.
core = core_path.read_text(encoding="utf-8")
if "[A11Y_RECONNECT_REBUILD]" not in core:
    needle = "            LockWindowManager.setCoreService(this)\n            LockWindowManager.init(this)\n"
    replacement = '''            LockWindowManager.setCoreService(this)
            LockWindowManager.init(this)

            // A11y reconnect invalidates TYPE_ACCESSIBILITY_OVERLAY ownership. If a
            // blacklist lock was already visible, resubmit the same lock under the new
            // generation. LockWindowManager will classify the old generation as stale,
            // remove only the stale physical view, then rebuild it immediately.
            val reconnectLockedPkg = LockWindowManager.getCurrentBlockedPackageName()
            if (LockWindowManager.isLocked &&
                LockWindowManager.isBlacklistLock &&
                !reconnectLockedPkg.isNullOrBlank()
            ) {
                Log.w(TAG, "[A11Y_RECONNECT_REBUILD] generation=$newGen pkg=$reconnectLockedPkg")
                LogExporter.logEvent(
                    "LockScreen",
                    "[A11Y_RECONNECT_REBUILD] generation=$newGen pkg=$reconnectLockedPkg"
                )
                LockWindowManager.showLockScreen(
                    reason = "Accessibility reconnect rebuild",
                    isBlacklist = true,
                    forceBypassGrace = true,
                    blockedPackageName = reconnectLockedPkg,
                    isScreenOnRedetection = true,
                    detectionSource = "A11Y_RECONNECT"
                )
            }
'''
    core = replace_once(core, needle, replacement, "a11y reconnect rebuild insertion")
    write(core_path, core)
else:
    print("CoreService reconnect rebuild patch already applied.")

print("Lock exit authority patch applied successfully.")
