$ErrorActionPreference = 'Stop'

$path = 'app/src/main/java/com/zhizhi/app/ui/LockWindowManager.kt'
$text = Get-Content -Raw -Encoding UTF8 $path

function Require-Contains([string]$haystack, [string]$needle, [string]$label) {
    if (-not $haystack.Contains($needle)) {
        throw "Patch anchor not found: $label"
    }
}

# 1) A successful overlay attach is a system action, not a user penalty hit.
$attachBlock = @'
                // Boundary 2 — real-display hit authority. This is the ONLY
                // place where penalty hit counting + transaction commit fires.
                // Deferred / cancelled / reasserted / blocked attaches bail
                // before reaching this line, so they can never increment.
                recordSuccessfulPenaltyEligibleDisplay(
                    context = context,
                    reason = reason,
                    isBlacklist = isBlacklist,
                    isScreenOnRedetection = isScreenOnRedetection
                )
'@
$attachReplacement = @'
                // Repeated-return-home penalty is user-action driven.
                // A successful overlay attach/re-attach is a system action and never increments it.
'@
Require-Contains $text $attachBlock 'addView penalty call'
$text = $text.Replace($attachBlock, $attachReplacement)

# 2) Replace the old addView-authority counter with the explicit RETURN_HOME authority.
$functionStart = $text.IndexOf('    private fun recordSuccessfulPenaltyEligibleDisplay(')
if ($functionStart -lt 0) { throw 'Patch anchor not found: recordSuccessfulPenaltyEligibleDisplay' }
$nextFunction = $text.IndexOf('    private fun issuePenaltyTransactionOnce(', $functionStart)
if ($nextFunction -lt 0) { throw 'Patch anchor not found: issuePenaltyTransactionOnce' }

$newFunction = @'
    /**
     * Repeated-return-home penalty authority.
     *
     * Only the explicit “回到桌面” button calls this method. Foreground detection,
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

# 3) The deterministic user action is the single authority that records a hit.
$homeAnchor = @'
            recordPendingReturnDesktopExit(lastBlockedPackageName ?: "unknown")
            clearBlacklistHitDebounceForUserAction()
            Log.i(
'@
$homeReplacement = @'
            recordPendingReturnDesktopExit(lastBlockedPackageName ?: "unknown")
            clearBlacklistHitDebounceForUserAction()
            recordReturnHomePenaltyHit(context, now, "RETURN_HOME_BUTTON")
            Log.i(
'@
Require-Contains $text $homeAnchor 'return-home button handler'
$text = $text.Replace($homeAnchor, $homeReplacement)

# 4) Product copy must describe the action actually being counted.
$text = $text.Replace('append("⚠️ 30秒内锁屏${PENALTY_THRESHOLD}次，触发惩罚")', 'append("⚠️ 30秒内返回桌面${PENALTY_THRESHOLD}次，触发惩罚")')
$text = $text.Replace('tvPenaltyHitCount?.text = "⚠️ 30秒内锁屏 ${mPenaltyHitCount}/${PENALTY_THRESHOLD} 次"', 'tvPenaltyHitCount?.text = "⚠️ 30秒内返回桌面 ${mPenaltyHitCount}/${PENALTY_THRESHOLD} 次"')
$text = $text.Replace('// --- 频繁锁屏惩罚 ---', '// --- 频繁返回桌面惩罚 ---')
$text = $text.Replace('private var mPenaltyHitCount = 0               // 当前窗口内锁屏出现次数', 'private var mPenaltyHitCount = 0               // 当前窗口内“回到桌面”按钮点击次数')

Set-Content -Path $path -Value $text -Encoding UTF8 -NoNewline

# Fail loudly if any old authority/copy survived the targeted migration.
$verify = Get-Content -Raw -Encoding UTF8 $path
if ($verify.Contains('recordSuccessfulPenaltyEligibleDisplay(')) {
    throw 'Old addView penalty authority still exists after patch'
}
if (-not $verify.Contains('recordReturnHomePenaltyHit(context, now, "RETURN_HOME_BUTTON")')) {
    throw 'Return-home penalty hook missing after patch'
}
if (-not $verify.Contains('30秒内返回桌面')) {
    throw 'Return-home penalty UI copy missing after patch'
}

Write-Host 'Return-home penalty patch applied successfully.'
