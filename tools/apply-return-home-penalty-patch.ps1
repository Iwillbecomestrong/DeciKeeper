$ErrorActionPreference = 'Stop'

$path = 'app/src/main/java/com/zhizhi/app/ui/LockWindowManager.kt'
$text = Get-Content -Raw -Encoding UTF8 $path

# 1) Remove the old addView-success penalty authority. Use ASCII anchors only so
# Windows PowerShell 5 does not depend on the script file's Unicode decoding.
$callStart = $text.IndexOf('                recordSuccessfulPenaltyEligibleDisplay(')
if ($callStart -lt 0) { throw 'Patch anchor not found: addView penalty call' }
$commentStart = $text.LastIndexOf('                // Boundary 2', $callStart)
if ($commentStart -lt 0) { $commentStart = $callStart }
$callClose = $text.IndexOf('                )', $callStart)
if ($callClose -lt 0) { throw 'Patch anchor not found: addView penalty call close' }
$callEnd = $text.IndexOf("`n", $callClose)
if ($callEnd -lt 0) { $callEnd = $callClose + '                )'.Length } else { $callEnd += 1 }
$attachReplacement = "                // Repeated-return-home penalty is user-action driven.`n" +
                     "                // Overlay attach/re-attach is a system action and never increments it.`n"
$text = $text.Substring(0, $commentStart) + $attachReplacement + $text.Substring($callEnd)

# 2) Replace the old addView-authority counter with the explicit RETURN_HOME authority.
$functionStart = $text.IndexOf('    private fun recordSuccessfulPenaltyEligibleDisplay(')
if ($functionStart -lt 0) { throw 'Patch anchor not found: recordSuccessfulPenaltyEligibleDisplay' }
$nextFunction = $text.IndexOf('    private fun issuePenaltyTransactionOnce(', $functionStart)
if ($nextFunction -lt 0) { throw 'Patch anchor not found: issuePenaltyTransactionOnce' }

$newFunction = @'
    /**
     * Repeated-return-home penalty authority.
     *
     * Only the explicit RETURN_HOME button calls this method. Foreground detection,
     * overlay attach/re-attach, Accessibility replay and OEM launcher noise have no
     * authority to increment the counter.
     */
    private fun recordReturnHomePenaltyHit(
        context: Context,
        now: Long,
        reason: String
    ): Boolean {
        val isDndActive = isBlacklistLock && TimeRuleManager.getCurrentSegment(context) != null
                && now >= CreditManager.getFreeTimeEnd(context)
        if (!isDndActive) {
            if (DefaultLogDebugConfig.quotaCalculation) {
                Log.d(TAG, "[Penalty] return-home click not eligible | blacklist=$isBlacklistLock, hasSegment=${TimeRuleManager.getCurrentSegment(context) != null}, freeTimeEnded=${now >= CreditManager.getFreeTimeEnd(context)}")
            }
            return false
        }

        if (now - mPenaltyWindowStart > PENALTY_WINDOW_MS) {
            mPenaltyWindowStart = now
            mPenaltyHitCount = 1
            if (DefaultLogDebugConfig.quotaCalculation) {
                Log.d(TAG, "[Penalty] return-home window reset, count=1/$PENALTY_THRESHOLD | reason=$reason")
            }
        } else {
            mPenaltyHitCount++
            if (DefaultLogDebugConfig.quotaCalculation) {
                Log.d(TAG, "[Penalty] return-home increment, count=$mPenaltyHitCount/$PENALTY_THRESHOLD | reason=$reason")
            }
        }

        if (mPenaltyHitCount >= PENALTY_THRESHOLD) {
            issuePenaltyTransactionOnce(context, now, reason)
        }
        return true
    }

'@
$text = $text.Substring(0, $functionStart) + $newFunction + $text.Substring($nextFunction)

# 3) Wire the deterministic user action as the single hit authority.
$homeAnchor = "            recordPendingReturnDesktopExit(lastBlockedPackageName ?: \"unknown\")`n            clearBlacklistHitDebounceForUserAction()`n"
$homeIndex = $text.IndexOf($homeAnchor)
if ($homeIndex -lt 0) { throw 'Patch anchor not found: return-home button handler' }
$homeReplacement = $homeAnchor + "            recordReturnHomePenaltyHit(context, now, \"RETURN_HOME_BUTTON\")`n"
$text = $text.Replace($homeAnchor, $homeReplacement)

# 4) Update user-visible copy using ASCII Kotlin unicode escapes.
$receiptAnchor = '        mPendingPenaltyMessage = buildString {'
$receiptStart = $text.IndexOf($receiptAnchor)
if ($receiptStart -lt 0) { throw 'Patch anchor not found: penalty receipt builder' }
$receiptLineStart = $text.IndexOf('            append("', $receiptStart)
$receiptLineEnd = $text.IndexOf("`n", $receiptLineStart)
if ($receiptLineStart -lt 0 -or $receiptLineEnd -lt 0) { throw 'Patch anchor not found: penalty receipt line' }
$receiptLine = '            append("\u26A0\uFE0F 30\u79D2\u5185\u8FD4\u56DE\u684C\u9762${PENALTY_THRESHOLD}\u6B21\uFF0C\u89E6\u53D1\u60E9\u7F5A")'
$text = $text.Substring(0, $receiptLineStart) + $receiptLine + $text.Substring($receiptLineEnd)

$uiAnchor = '        tvPenaltyHitCount?.text = "'
$uiStart = $text.IndexOf($uiAnchor)
if ($uiStart -lt 0) { throw 'Patch anchor not found: penalty hit UI line' }
$uiEnd = $text.IndexOf("`n", $uiStart)
if ($uiEnd -lt 0) { throw 'Patch anchor not found: penalty hit UI line end' }
$uiLine = '        tvPenaltyHitCount?.text = "\u26A0\uFE0F 30\u79D2\u5185\u8FD4\u56DE\u684C\u9762 ${mPenaltyHitCount}/${PENALTY_THRESHOLD} \u6B21"'
$text = $text.Substring(0, $uiStart) + $uiLine + $text.Substring($uiEnd)

# Write Kotlin as UTF-8 without BOM.
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Resolve-Path $path), $text, $utf8NoBom)

# Verify the authority migration with ASCII anchors only.
$verify = Get-Content -Raw -Encoding UTF8 $path
if ($verify.Contains('recordSuccessfulPenaltyEligibleDisplay(')) {
    throw 'Old addView penalty authority still exists after patch'
}
if (-not $verify.Contains('recordReturnHomePenaltyHit(context, now, "RETURN_HOME_BUTTON")')) {
    throw 'Return-home penalty hook missing after patch'
}
if (-not $verify.Contains('\u8FD4\u56DE\u684C\u9762')) {
    throw 'Return-home penalty UI copy missing after patch'
}

# CI-only normalization for a pre-existing dev-branch resource issue. The XML currently
# starts with blank lines before the XML declaration, which Android rejects. Do not add
# this resource to the task commit; normalize it only in the verification workspace.
$xmlPath = 'app/src/main/res/layout/dialog_alarm_default_settings.xml'
$xml = Get-Content -Raw -Encoding UTF8 $xmlPath
$normalizedXml = $xml.TrimStart([char[]]@("`r", "`n", " ", "`t"))
[System.IO.File]::WriteAllText((Resolve-Path $xmlPath), $normalizedXml, $utf8NoBom)

Write-Host 'Return-home penalty patch applied successfully.'
