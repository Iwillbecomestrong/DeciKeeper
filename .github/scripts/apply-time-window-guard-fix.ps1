$ErrorActionPreference = 'Stop'

$path = 'app/src/main/java/com/ironwill/app/service/MonitorService.kt'
$text = [System.IO.File]::ReadAllText($path)

$fieldMarker = '    private val foregroundDetectionLogCompressor = ForegroundDetectionLogCompressor()'
$fieldReplacement = @'
    private val foregroundDetectionLogCompressor = ForegroundDetectionLogCompressor()
    private val foregroundGuardFallbackGate = ForegroundGuardFallbackGate()
'@

if (-not $text.Contains('private val foregroundGuardFallbackGate = ForegroundGuardFallbackGate()')) {
    if (-not $text.Contains($fieldMarker)) {
        throw 'MonitorService field marker not found'
    }
    $text = $text.Replace($fieldMarker, $fieldReplacement.TrimEnd("`r", "`n"))
}

$oldGuard = @'
        val guardResult = shouldEnforceLockForCurrentTop(effectivePackageName, now)
        val passedForegroundGuard = allowByFallback || forceRelockAfterTemporaryAccess || forcePostTempAccessRelock || isDetachedRelock || guardResult
'@

$newGuard = @'
        val guardResult = shouldEnforceLockForCurrentTop(effectivePackageName, now)
        val sustainedForegroundFallbackEligible =
            isBlacklisted && isDndLocked && isSustainedForegroundFallbackEligible(
                evidenceSource = mLastTopEvidence.name,
                selectedPackage = effectivePackageName,
                cachedPackage = mLastTopPackage,
                lastForegroundEventTs = mLastForegroundEventTs,
                lastBackgroundEventTs = mLastBackgroundEventTs
            )
        val forceGuardFallback = foregroundGuardFallbackGate.shouldForce(
            packageName = effectivePackageName,
            nowMs = now,
            eligible = sustainedForegroundFallbackEligible,
            guardPassed = guardResult
        )
        if (forceGuardFallback) {
            Log.w(
                TAG,
                "[ForegroundGuard] Force lock after 60s sustained CACHE/HISTORY uncertainty: pkg=$effectivePackageName"
            )
        }
        val passedForegroundGuard = allowByFallback || forceRelockAfterTemporaryAccess ||
            forcePostTempAccessRelock || isDetachedRelock || guardResult || forceGuardFallback
'@

if (-not $text.Contains('val sustainedForegroundFallbackEligible =')) {
    $oldGuardText = $oldGuard.Trim("`r", "`n")
    if (-not $text.Contains($oldGuardText)) {
        throw 'MonitorService guard marker not found'
    }
    $text = $text.Replace($oldGuardText, $newGuard.Trim("`r", "`n"))
}

[System.IO.File]::WriteAllText(
    $path,
    $text,
    [System.Text.UTF8Encoding]::new($false)
)

Write-Host 'MonitorService time-window guard fallback patch is present.'
