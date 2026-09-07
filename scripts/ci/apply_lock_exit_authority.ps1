$ErrorActionPreference = 'Stop'

function Replace-RegexOnce {
    param(
        [string]$Text,
        [string]$Pattern,
        [string]$Replacement,
        [string]$Label
    )
    $rx = [regex]::new($Pattern, [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $matches = $rx.Matches($Text)
    if ($matches.Count -ne 1) {
        throw "[$Label] expected exactly one match, got $($matches.Count)"
    }
    return $rx.Replace($Text, $Replacement, 1)
}

function Replace-LiteralOnce {
    param(
        [string]$Text,
        [string]$Old,
        [string]$New,
        [string]$Label
    )
    $first = $Text.IndexOf($Old, [System.StringComparison]::Ordinal)
    if ($first -lt 0) {
        throw "[$Label] expected literal was not found"
    }
    $second = $Text.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) {
        throw "[$Label] expected exactly one literal match"
    }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

$lockPath = 'app/src/main/java/com/zhizhi/app/ui/LockWindowManager.kt'
$monitorPath = 'app/src/main/java/com/zhizhi/app/service/MonitorService.kt'
$corePath = 'app/src/main/java/com/zhizhi/app/service/CoreService.kt'

# -----------------------------------------------------------------------------
# LockWindowManager: make final dismissal compile-time explicit and keep
# screen-off / natural rule transitions from becoming hidden fifth exits.
# -----------------------------------------------------------------------------
$lock = Get-Content -Raw -Encoding UTF8 $lockPath
if (-not $lock.Contains('fun dismissLockScreen(authority: LockDismissAuthority)')) {
    $lock = Replace-LiteralOnce $lock `
        'fun dismissLockScreen(force: Boolean = false) {' `
        'fun dismissLockScreen(authority: LockDismissAuthority) {' `
        'dismiss signature'

    $lock = Replace-RegexOnce $lock `
        '\s*if \(isBlacklistLock && !force\) \{\s*Log\.d\(TAG, "Dismiss rejected: Blacklist Lock Active \(use force=true or button click\)"\)\s*return\s*\}\s*' `
        "`n        Log.i(TAG, \"[LOCK_EXIT] authority=`$authority pkg=`${lastBlockedPackageName ?: \\\"unknown\\\"}\")`n`n" `
        'remove force bypass guard'

    $lock = Replace-LiteralOnce $lock `
        'dismissLockScreen(true)' `
        'dismissLockScreen(LockDismissAuthority.RETURN_HOME)' `
        'return-home authority'

    $lock = Replace-RegexOnce $lock `
        '(resetConsecutiveNormalLockState\("free_time_granted_gap"\)\s*totalSegmentBlacklistHits = 0\s*)dismissLockScreen\(force = true\)' `
        '$1dismissLockScreen(LockDismissAuthority.REST)' `
        'gap-rest authority'

    $lock = Replace-RegexOnce $lock `
        '(resetConsecutiveNormalLockState\("weekly_privilege_granted"\)\s*totalSegmentBlacklistHits = 0\s*)dismissLockScreen\(force = true\)' `
        '$1dismissLockScreen(LockDismissAuthority.WEEKLY_PRIVILEGE)' `
        'weekly-privilege authority'

    $lock = Replace-RegexOnce $lock `
        '(resetConsecutiveNormalLockState\("free_time_granted"\)\s*totalSegmentBlacklistHits = 0\s*)dismissLockScreen\(force = true\)' `
        '$1dismissLockScreen(LockDismissAuthority.REST)' `
        'rest authority'

    $lock = Replace-RegexOnce $lock `
        'LogExporter\.i\("LockScreen", "\[AUTO_RELEASE\] restrictions cleared for pkg=\$targetPackage"\)\s*dismissLockScreen\(force = true\)\s*return true' `
        'LogExporter.i("LockScreen", "[AUTO_RELEASE_BLOCKED] restrictions cleared for pkg=$targetPackage but visible lock requires explicit exit")`n        return false' `
        'block automatic overlay release'

    $lock = Replace-RegexOnce $lock `
        '(endTemporaryAccessLifecycle\(nowMs, reason = "FREE_TIME_STARTED", nextState = "FREE_TIME"\)\s*clearTemporaryAccessState\(\)\s*)dismissLockScreen\(force = true\)' `
        '$1LogExporter.i("TemporaryAccess", "[NATURAL_FREEDOM] visible lock preserved; explicit exit required")' `
        'natural-freedom overlay preserve'

    $lock = Replace-RegexOnce $lock `
        'internal fun shouldDismissForScreenOff\(\s*isAlarmOverlayActive: Boolean,\s*isLocked: Boolean,\s*isBlacklistLock: Boolean\s*\): Boolean \{.*?\n    \}' `
        "internal fun shouldDismissForScreenOff(`n        isAlarmOverlayActive: Boolean,`n        isLocked: Boolean,`n        isBlacklistLock: Boolean`n    ): Boolean {`n        // Screen-off is not one of the four explicit exits. Preserve every visible lock.`n        return false`n    }" `
        'screen-off policy'

    $lock = Replace-LiteralOnce $lock `
        'resetLockState("ScreenOff")' `
        'LogExporter.logEvent("LockScreen", "[SCREEN_OFF_PRESERVE] visible lock kept; explicit exit required")' `
        'remove screen-off reset'

    if ($lock.Contains('dismissLockScreen(force = true)') -or $lock.Contains('dismissLockScreen(true)')) {
        throw '[LockWindowManager] legacy force-dismiss call remains after patch'
    }
    Set-Content -Encoding UTF8 -NoNewline $lockPath $lock
} else {
    Write-Host 'LockWindowManager authority patch already applied.'
}

# -----------------------------------------------------------------------------
# MonitorService: detection is allowed to clear Presence state, never the lock.
# Automatic restriction release may settle sessions, but cannot dismiss overlay.
# -----------------------------------------------------------------------------
$monitor = Get-Content -Raw -Encoding UTF8 $monitorPath
if ($monitor.Contains('LockWindowManager.dismissLockScreen')) {
    $monitor = Replace-RegexOnce $monitor `
        '/\*\* 小窗关闭后解除锁机（冷却期内不解锁，冷却结束前仍保持锁屏） \*/\s*private fun handlePresenceUnlock\(pkg: String, reason: String\) \{.*?\n    \}\s*\n\s*/\*\* 重置检测状态 \*/' `
        "/** Presence 只负责清理小窗检测状态，不拥有解锁权。 */`n    private fun handlePresenceUnlock(pkg: String, reason: String) {`n        val lockedFor = (System.currentTimeMillis() - mPresentLockedAtMs).coerceAtLeast(0L)`n        Log.i(TAG, \"[Presence] CLEAR_ONLY: pkg=`$pkg reason=`$reason lockedFor=`${lockedFor}ms\")`n        LogExporter.logEvent(\"MonitorService\", \"[Presence_CLEAR_ONLY] pkg=`$pkg reason=`$reason lockedFor=`${lockedFor}ms\")`n        mPresentLockedPkg = null`n        mPresentLockedAtMs = 0L`n        mPresenceStatUnlocked++`n    }`n`n    /** 重置检测状态 */" `
        'presence clear-only'

    $monitor = Replace-RegexOnce $monitor `
        'if \(decision\.dismissBlacklistOverlay\) \{\s*LockWindowManager\.dismissLockScreen\(force = true\)\s*currentLockedPkg = null\s*\}' `
        'if (decision.dismissBlacklistOverlay) {`n            Log.w(TAG, "[AUTO_RELEASE_BLOCKED] visible lock requires explicit exit")`n        }' `
        'monitor automatic release'

    if ($monitor.Contains('LockWindowManager.dismissLockScreen')) {
        throw '[MonitorService] detection path still directly dismisses the lock'
    }
    Set-Content -Encoding UTF8 -NoNewline $monitorPath $monitor
} else {
    Write-Host 'MonitorService dismiss-authority patch already applied.'
}

# -----------------------------------------------------------------------------
# CoreService: Accessibility reconnect is a rebuild trigger, not an unlock.
# Re-submit the existing lock after the generation bump so LockWindowManager's
# generation classifier destroys only the stale physical overlay and recreates it.
# -----------------------------------------------------------------------------
$core = Get-Content -Raw -Encoding UTF8 $corePath
if (-not $core.Contains('[A11Y_RECONNECT_REBUILD]')) {
    $needle = @'
            LockWindowManager.setCoreService(this)
            LockWindowManager.init(this)

            // 馃殌 [Fix] Explicitly start MonitorService to ensure background logic runs
'@
    $replacement = @'
            LockWindowManager.setCoreService(this)
            LockWindowManager.init(this)

            // A11y reconnect invalidates TYPE_ACCESSIBILITY_OVERLAY ownership.  If a
            // blacklist lock was already visible, immediately resubmit the same lock
            // under the new generation.  This is an internal rebuild, never an unlock.
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

            // 馃殌 [Fix] Explicitly start MonitorService to ensure background logic runs
'@
    $core = Replace-LiteralOnce $core $needle $replacement 'a11y reconnect rebuild insertion'
    Set-Content -Encoding UTF8 -NoNewline $corePath $core
} else {
    Write-Host 'CoreService reconnect rebuild patch already applied.'
}

Write-Host 'Lock exit authority patch applied successfully.'
