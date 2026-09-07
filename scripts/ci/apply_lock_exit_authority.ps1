$ErrorActionPreference = 'Stop'

function Replace-RegexOnce {
    param([string]$Text, [string]$Pattern, [string]$Replacement, [string]$Label)
    $rx = [regex]::new($Pattern, [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $matches = $rx.Matches($Text)
    if ($matches.Count -ne 1) { throw "[$Label] expected exactly one match, got $($matches.Count)" }
    return $rx.Replace($Text, $Replacement, 1)
}

function Replace-LiteralOnce {
    param([string]$Text, [string]$Old, [string]$New, [string]$Label)
    $first = $Text.IndexOf($Old, [System.StringComparison]::Ordinal)
    if ($first -lt 0) { throw "[$Label] expected literal was not found" }
    $second = $Text.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) { throw "[$Label] expected exactly one literal match" }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

function Write-Utf8NoBom {
    param([string]$Path, [string]$Text)
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false))
}

$lockPath = 'app/src/main/java/com/zhizhi/app/ui/LockWindowManager.kt'
$monitorPath = 'app/src/main/java/com/zhizhi/app/service/MonitorService.kt'
$corePath = 'app/src/main/java/com/zhizhi/app/service/CoreService.kt'

$lock = Get-Content -Raw -Encoding UTF8 $lockPath
if (-not $lock.Contains('fun dismissLockScreen(authority: LockDismissAuthority)')) {
    $lock = Replace-LiteralOnce $lock 'fun dismissLockScreen(force: Boolean = false) {' 'fun dismissLockScreen(authority: LockDismissAuthority) {' 'dismiss signature'

    $lockExitLog = @'

        Log.i(TAG, "[LOCK_EXIT] authority=$authority pkg=$lastBlockedPackageName")

'@
    $lock = Replace-RegexOnce $lock '\s*if \(isBlacklistLock && !force\) \{\s*Log\.d\(TAG, "Dismiss rejected: Blacklist Lock Active \(use force=true or button click\)"\)\s*return\s*\}\s*' $lockExitLog 'remove force bypass guard'

    $lock = Replace-LiteralOnce $lock 'dismissLockScreen(true)' 'dismissLockScreen(LockDismissAuthority.RETURN_HOME)' 'return-home authority'
    $lock = Replace-RegexOnce $lock '(resetConsecutiveNormalLockState\("free_time_granted_gap"\)\s*totalSegmentBlacklistHits = 0\s*)dismissLockScreen\(force = true\)' '$1dismissLockScreen(LockDismissAuthority.REST)' 'gap-rest authority'
    $lock = Replace-RegexOnce $lock '(resetConsecutiveNormalLockState\("weekly_privilege_granted"\)\s*totalSegmentBlacklistHits = 0\s*)dismissLockScreen\(force = true\)' '$1dismissLockScreen(LockDismissAuthority.WEEKLY_PRIVILEGE)' 'weekly-privilege authority'
    $lock = Replace-RegexOnce $lock '(resetConsecutiveNormalLockState\("free_time_granted"\)\s*totalSegmentBlacklistHits = 0\s*)dismissLockScreen\(force = true\)' '$1dismissLockScreen(LockDismissAuthority.REST)' 'rest authority'

    $autoReleaseReplacement = @'
LogExporter.i("LockScreen", "[AUTO_RELEASE_BLOCKED] restrictions cleared for pkg=$targetPackage but visible lock requires explicit exit")
        return false
'@
    $lock = Replace-RegexOnce $lock 'LogExporter\.i\("LockScreen", "\[AUTO_RELEASE\] restrictions cleared for pkg=\$targetPackage"\)\s*dismissLockScreen\(force = true\)\s*return true' $autoReleaseReplacement 'block automatic overlay release'

    $naturalFreedomReplacement = @'
$1LogExporter.i("TemporaryAccess", "[NATURAL_FREEDOM] visible lock preserved; explicit exit required")
'@
    $lock = Replace-RegexOnce $lock '(endTemporaryAccessLifecycle\(nowMs, reason = "FREE_TIME_STARTED", nextState = "FREE_TIME"\)\s*clearTemporaryAccessState\(\)\s*)dismissLockScreen\(force = true\)' $naturalFreedomReplacement 'natural-freedom overlay preserve'

    $screenOffPolicy = @'
internal fun shouldDismissForScreenOff(
        isAlarmOverlayActive: Boolean,
        isLocked: Boolean,
        isBlacklistLock: Boolean
    ): Boolean {
        // Screen-off is not one of the four explicit exits. Preserve every visible lock.
        return false
    }
'@
    $lock = Replace-RegexOnce $lock 'internal fun shouldDismissForScreenOff\(\s*isAlarmOverlayActive: Boolean,\s*isLocked: Boolean,\s*isBlacklistLock: Boolean\s*\): Boolean \{.*?\n    \}' $screenOffPolicy 'screen-off policy'
    $lock = Replace-LiteralOnce $lock 'resetLockState("ScreenOff")' 'LogExporter.logEvent("LockScreen", "[SCREEN_OFF_PRESERVE] visible lock kept; explicit exit required")' 'remove screen-off reset'

    if ($lock.Contains('dismissLockScreen(force = true)') -or $lock.Contains('dismissLockScreen(true)')) {
        throw '[LockWindowManager] legacy force-dismiss call remains after patch'
    }
    Write-Utf8NoBom $lockPath $lock
} else {
    Write-Host 'LockWindowManager authority patch already applied.'
}

$monitor = Get-Content -Raw -Encoding UTF8 $monitorPath
if ($monitor.Contains('LockWindowManager.dismissLockScreen')) {
    $presenceReplacement = @'
/** Presence 只负责清理小窗检测状态，不拥有解锁权。 */
    private fun handlePresenceUnlock(pkg: String, reason: String) {
        val lockedFor = (System.currentTimeMillis() - mPresentLockedAtMs).coerceAtLeast(0L)
        Log.i(TAG, "[Presence] CLEAR_ONLY: pkg=$pkg reason=$reason lockedFor=${lockedFor}ms")
        LogExporter.logEvent("MonitorService", "[Presence_CLEAR_ONLY] pkg=$pkg reason=$reason lockedFor=${lockedFor}ms")
        mPresentLockedPkg = null
        mPresentLockedAtMs = 0L
        mPresenceStatUnlocked++
    }

    /** 重置检测状态 */
'@
    $monitor = Replace-RegexOnce $monitor '/\*\* 小窗关闭后解除锁机（冷却期内不解锁，冷却结束前仍保持锁屏） \*/\s*private fun handlePresenceUnlock\(pkg: String, reason: String\) \{.*?\n    \}\s*\n\s*/\*\* 重置检测状态 \*/' $presenceReplacement 'presence clear-only'

    $monitorAutoReleaseReplacement = @'
if (decision.dismissBlacklistOverlay) {
            Log.w(TAG, "[AUTO_RELEASE_BLOCKED] visible lock requires explicit exit")
        }
'@
    $monitor = Replace-RegexOnce $monitor 'if \(decision\.dismissBlacklistOverlay\) \{\s*LockWindowManager\.dismissLockScreen\(force = true\)\s*currentLockedPkg = null\s*\}' $monitorAutoReleaseReplacement 'monitor automatic release'

    if ($monitor.Contains('LockWindowManager.dismissLockScreen')) {
        throw '[MonitorService] detection path still directly dismisses the lock'
    }
    Write-Utf8NoBom $monitorPath $monitor
} else {
    Write-Host 'MonitorService dismiss-authority patch already applied.'
}

$core = Get-Content -Raw -Encoding UTF8 $corePath
if (-not $core.Contains('[A11Y_RECONNECT_REBUILD]')) {
    $needle = @'
            LockWindowManager.setCoreService(this)
            LockWindowManager.init(this)
'@
    $replacement = @'
            LockWindowManager.setCoreService(this)
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
'@
    $core = Replace-LiteralOnce $core $needle $replacement 'a11y reconnect rebuild insertion'
    Write-Utf8NoBom $corePath $core
} else {
    Write-Host 'CoreService reconnect rebuild patch already applied.'
}

Write-Host 'Lock exit authority patch applied successfully.'
