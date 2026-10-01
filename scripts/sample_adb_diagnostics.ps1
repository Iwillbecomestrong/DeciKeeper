param (
    [string]$OutputDir = "diagnostics",
    [int]$IntervalSec = 2,
    [string]$Device = ""
)

$targetDir = Join-Path (Get-Location) $OutputDir
if (-not (Test-Path $targetDir)) {
    New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
}

$activityLog = Join-Path $targetDir "adb_activity.txt"
$windowLog = Join-Path $targetDir "adb_window.txt"
$diagLog = Join-Path $targetDir "decikeeper_diag_logcat.txt"

Write-Host "=========================================================="
Write-Host "DeciKeeper Vivo Foreground Diagnosis Sampler Started"
Write-Host "Output: $targetDir"
Write-Host "Interval: ${IntervalSec}s"
Write-Host "=========================================================="

$adbCmd = "adb"
$deviceArgs = if ($Device -ne "") { @("-s", $Device) } else { @() }

# Background logcat capture for FG_* and EXPIRY_* tags
$logcatProc = Start-Process -FilePath "adb" -ArgumentList ($deviceArgs + @("logcat", "-v", "time", "*:D")) -RedirectStandardOutput $diagLog -PassThru -NoNewWindow

try {
    while ($true) {
        $now = Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff"
        
        # 1. dumpsys activity activities
        $actOutput = & adb @deviceArgs shell dumpsys activity activities 2>&1
        $filteredAct = $actOutput | Select-String -Pattern "topResumedActivity|mResumedActivity|ResumedActivity|com\.anthropic\.claude|mFocusedRootTask|mFocusedWindow"
        
        $actHeader = "[$now] ===== dumpsys activity ====="
        Add-Content -Path $activityLog -Value $actHeader
        if ($filteredAct) {
            $filteredAct | ForEach-Object { Add-Content -Path $activityLog -Value $_.Line }
        } else {
            Add-Content -Path $activityLog -Value "No matched activity lines"
        }
        Add-Content -Path $activityLog -Value ""

        # 2. dumpsys window
        $winOutput = & adb @deviceArgs shell dumpsys window 2>&1
        $filteredWin = $winOutput | Select-String -Pattern "mCurrentFocus|mFocusedApp|com\.anthropic\.claude|mInputMethodTarget"

        $winHeader = "[$now] ===== dumpsys window ====="
        Add-Content -Path $windowLog -Value $winHeader
        if ($filteredWin) {
            $filteredWin | ForEach-Object { Add-Content -Path $windowLog -Value $_.Line }
        } else {
            Add-Content -Path $windowLog -Value "No matched window lines"
        }
        Add-Content -Path $windowLog -Value ""

        Write-Host "[$now] Sampled activity & window state..."
        Start-Sleep -Seconds $IntervalSec
    }
} finally {
    if ($logcatProc -and -not $logcatProc.HasExited) {
        Stop-Process -Id $logcatProc.Id -Force -ErrorAction SilentlyContinue
    }
    Write-Host "Sampler stopped."
}
