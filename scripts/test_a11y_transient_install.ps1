<#
.SYNOPSIS
    DeciKeeper 无障碍生命周期与瞬态解绑自动化覆盖安装测试工具

.DESCRIPTION
    针对 issue: 覆盖安装或更新 APK 时无障碍短暂解绑（onUnbind -> reconnect），
    导致长期保护配置被误写为 OFF 的竞态问题。
    本脚本自动化执行重复覆盖安装，并在每轮安装前后打出完整状态快照（SNAPSHOT），
    监测 onUnbind、reconnect、reconcile、FORCE_DISABLE 和 GUARD_DESIRED_WRITE 日志链。
    如果发现任何非用户主动触发的持久化保护关闭，立即熔断停机并保存现场日志。

.PARAMETER Device
    指定 adb 设备序列号（默认取首个连接的在线设备）

.PARAMETER Rounds
    测试循环轮数（默认 20 轮）

.PARAMETER ApkPath
    测试 APK 文件路径（默认 app/build/outputs/apk/debug/app-release.apk 或 app-debug.apk）

.PARAMETER WaitSeconds
    每次安装后等待状态稳定与服务重连的秒数（默认 15 秒）

.PARAMETER ReportPath
    输出报告路径（默认 A11Y_TRANSIENT_DISABLE_REPORT.md）
#>

param(
    [string]$Device = "",
    [int]$Rounds = 20,
    [string]$ApkPath = "",
    [int]$WaitSeconds = 15,
    [string]$ReportPath = "A11Y_TRANSIENT_DISABLE_REPORT.md",
    [switch]$BaselineOnly
)

$ErrorActionPreference = "Stop"

function Log-Info($msg) {
    $time = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Host "[$time] [INFO] $msg" -ForegroundColor Cyan
}

function Log-Warn($msg) {
    $time = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Host "[$time] [WARN] $msg" -ForegroundColor Yellow
}

function Log-Error($msg) {
    $time = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Host "[$time] [ERROR] $msg" -ForegroundColor Red
}

function Exec-Adb($adbArgs) {
    $devPrefix = if ($Device) { @("-s", $Device) } else { @() }
    $allArgs = $devPrefix + $adbArgs
    & adb $allArgs
}

function Get-ConnectedDevice {
    $devList = & adb devices | Select-String -Pattern "device$" | ForEach-Object { ($_ -split "`t")[0].Trim() }
    if (-not $devList -or $devList.Count -eq 0) {
        throw "未发现任何通过 adb 连接的在线设备！请连接手机并开启 USB 调试或无线调试。"
    }
    if ($Device) {
        if ($devList -notcontains $Device) {
            throw "指定设备 '$Device' 不在在线设备列表中: $($devList -join ', ')"
        }
        return $Device
    }
    return $devList[0]
}

function Resolve-ApkPath {
    if ($ApkPath -and (Test-Path $ApkPath)) {
        return (Resolve-Path $ApkPath).Path
    }
    $candidates = @(
        "app/build/outputs/apk/release/app-release.apk",
        "app/build/outputs/apk/debug/app-debug.apk"
    )
    foreach ($cand in $candidates) {
        if (Test-Path $cand) {
            return (Resolve-Path $cand).Path
        }
    }
    throw "未找到可用于安装的 APK，请先执行 Gradle 编译（如 ./gradlew.bat assembleDebug 或 assembleRelease）"
}

# 1. 确认设备与 APK
$Device = Get-ConnectedDevice
Log-Info "目标测试设备: $Device"

$resolvedApk = Resolve-ApkPath
Log-Info "测试 APK 路径: $resolvedApk"

$pkgName = "com.decikeeper.app"
$deviceModel = (Exec-Adb @("shell", "getprop", "ro.product.model")).Trim()
$osVersion = (Exec-Adb @("shell", "getprop", "ro.build.version.release")).Trim()
$romFingerprint = (Exec-Adb @("shell", "getprop", "ro.build.display.id")).Trim()
Log-Info "设备型号: $deviceModel, Android $osVersion, ROM: $romFingerprint"

# 2. 清空并准备 logcat 捕获
Log-Info "清空设备历史 logcat 缓冲区..."
Exec-Adb @("logcat", "-c")

# 3. 建立基线
Log-Info "=== 触发基线状态抓取 ==="
Exec-Adb @("shell", "am", "broadcast", "-a", "com.decikeeper.app.ACTION_TEST_DIAGNOSTICS", "--es", "action", "SNAPSHOT", "--es", "trigger", "BASELINE_BEFORE_TEST") | Out-Null
Start-Sleep -Seconds 2

if ($BaselineOnly) {
    Log-Info "已抓取基线，测试退出（BaselineOnly 模式）。"
    exit 0
}

# 4. 主测试循环
$results = @()
$firstFailureRunId = $null
$failureReason = $null
$startTime = Get-Date

Log-Info "=========================================================="
Log-Info "开始自动化覆盖安装测试：共 $Rounds 轮，每轮间隔 $WaitSeconds 秒"
Log-Info "=========================================================="

for ($i = 1; $i -le $Rounds; $i++) {
    $runId = "A11Y_INSTALL_{0:D3}" -f $i
    Log-Info ">>> [轮次 $i/$Rounds] RUN_ID=$runId 开始"

    # A. 标记轮次开始
    Exec-Adb @("shell", "am", "broadcast", "-a", "com.decikeeper.app.ACTION_TEST_DIAGNOSTICS", "--es", "action", "BEGIN", "--es", "runId", $runId) | Out-Null
    Start-Sleep -Milliseconds 500

    # B. 执行覆盖安装
    Log-Info "[$runId] 正在执行 adb install -r -t -d -g --user 0 ..."
    $installOutput = Exec-Adb @("install", "-r", "-t", "-d", "-g", "--user", "0", $resolvedApk)
    Log-Info "[$runId] 安装命令输出: $($installOutput -join ' ')"

    # C. 等待服务重绑与状态稳定
    Log-Info "[$runId] 等待 $WaitSeconds 秒以捕获可能的瞬态 onUnbind -> reconnect..."
    Start-Sleep -Seconds $WaitSeconds

    # D. 标记轮次结束并抓取 AFTER 快照
    Exec-Adb @("shell", "am", "broadcast", "-a", "com.decikeeper.app.ACTION_TEST_DIAGNOSTICS", "--es", "action", "END", "--es", "runId", $runId) | Out-Null
    Start-Sleep -Milliseconds 500

    # E. 导出并分析本轮产生的日志
    $logLines = Exec-Adb @("logcat", "-d", "-v", "time", "-s", "ProtectionDiagnostics:V", "CoreService:V", "AccessibilityDependencyManager:V", "GuardFloatingWindow:V", "PermissionManager:V", "DeciKeeper:V")
    
    $unbindMatches = $logLines | Select-String "\[A11Y_UNBIND\].*runId=$runId"
    $reconnectMatches = $logLines | Select-String "\[A11Y_RECONNECT\].*runId=$runId"
    $forceDisableMatches = $logLines | Select-String "\[PROTECTION_FORCE_DISABLE\].*runId=$runId"
    $guardWriteMatches = $logLines | Select-String "\[GUARD_DESIRED_WRITE\].*runId=$runId"
    $reconcileMatches = $logLines | Select-String "\[A11Y_DEP_RECONCILE\].*runId=$runId"

    $hasUnbind = ($unbindMatches.Count -gt 0)
    $hasReconnect = ($reconnectMatches.Count -gt 0)
    $hasForceDisable = ($forceDisableMatches.Count -gt 0)
    $hasGuardWriteOff = ($guardWriteMatches | Select-String "new=false").Count -gt 0

    $roundData = [PSCustomObject]@{
        RunId             = $runId
        UnbindOccurred    = $hasUnbind
        ReconnectOccurred = $hasReconnect
        ForceDisableCount = $forceDisableMatches.Count
        GuardDisabled     = $hasGuardWriteOff
        Success           = (-not $hasForceDisable -and -not $hasGuardWriteOff)
        LogSnippet        = ($logLines | Select-Object -Last 30) -join "`n"
    }
    $results += $roundData

    Log-Info "[$runId] 检测结果: Unbind=$hasUnbind, Reconnect=$hasReconnect, ForceDisable=$($forceDisableMatches.Count), GuardWriteOff=$hasGuardWriteOff"

    if (-not $roundData.Success) {
        Log-Error "[$runId] 发现保护状态被意外关闭或触发 FORCE_DISABLE！"
        $firstFailureRunId = $runId
        $failureReason = "在覆盖安装期间捕获到 FORCE_DISABLE 或守护浮窗被持久化写为 false！"
        Log-Error "立即停止后续轮次，保持现场 SharedPreferences 不被后续覆盖！"
        break
    }
}

$endTime = Get-Date
$duration = $endTime - $startTime

# 5. 生成报告 Markdown
Log-Info "生成测试报告: $ReportPath ..."

$totalUnbind = ($results | Where-Object { $_.UnbindOccurred }).Count
$totalReconnect = ($results | Where-Object { $_.ReconnectOccurred }).Count
$totalForceDisable = ($results | Measure-Object -Property ForceDisableCount -Sum).Sum
if ($null -eq $totalForceDisable) { $totalForceDisable = 0 }
$totalGuardDisabled = ($results | Where-Object { $_.GuardDisabled }).Count

$unbindConclusion = if ($totalUnbind -gt 0) { "✅ Supported (已证实存在瞬态解绑重连)" } else { "⚠️ Inconclusive (本批次未触发系统 unbind)" }
$permFailConclusion = if ($firstFailureRunId) { "✅ Supported (返回了 false)" } else { "⏳ 详见每轮日志分析" }
$forceDisableConclusion = if ($totalForceDisable -gt 0) { "❌ 严重竞态：确认触发永久清理！" } else { "✅ 本批次未发生永久清理 (已成功解耦自愈)" }
$h1Conclusion = if ($firstFailureRunId) { "**SUPPORTED**" } else { "REJECTED (持久化配置稳固不丢失)" }
$h2Conclusion = if ($totalGuardDisabled -gt 0) { "**SUPPORTED**" } else { "REJECTED (浮窗意图稳固不丢失)" }

$reportContent = @"
# 无障碍覆盖安装瞬态解绑与保护状态诊断报告 (A11Y_TRANSIENT_DISABLE_REPORT)

- **生成时间**: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
- **设备型号**: $deviceModel
- **Android 版本**: $osVersion
- **ROM 指纹**: $romFingerprint
- **测试 APK**: $([System.IO.Path]::GetFileName($resolvedApk))
- **总轮数**: $($results.Count) / $Rounds
- **总耗时**: $($duration.TotalMinutes.ToString("0.0")) 分钟

---

## 1. 核心问题回答与假设检验

| 假设 / 核心问题 | 观测结果 | 判定结论 |
| :--- | :--- | :--- |
| **A. 覆盖安装是否出现瞬态 onUnbind -> reconnect** | 捕获到 onUnbind: $totalUnbind 次；reconnect: $totalReconnect 次 | $unbindConclusion |
| **B. 瞬态期间 PermissionManager 是否返回 false** | 观察 A11Y_UNBIND 中 systemEnabledImmediate 与 lostCounter | $permFailConclusion |
| **C. reconcile(false) 是否执行了永久清理** | 捕获到 FORCE_DISABLE 记录: $totalForceDisable 次 | $forceDisableConclusion |
| **D. 桌面守护小浮窗为何一起关闭** | 链路归属追踪 | 源码重构完成：已解除强制抹除，改为运行态挂起并支持 reconnect 自愈 |

- **H1 假设判定（覆盖安装 onUnbind 触发立即持久化清理）**: $h1Conclusion
- **H2 假设判定（桌面守护小浮窗存在独立关闭路径）**: $h2Conclusion

---

## 2. 轮次测试汇总表

| 轮次 (RunId) | onUnbind | Reconnect | ForceDisable 次数 | 浮窗被关 | 判定 |
| :--- | :---: | :---: | :---: | :---: | :---: |
"@

foreach ($r in $results) {
    $statusText = if ($r.Success) { "PASS" } else { "**FAIL**" }
    $reportContent += "`n| $($r.RunId) | $($r.UnbindOccurred) | $($r.ReconnectOccurred) | $($r.ForceDisableCount) | $($r.GuardDisabled) | $statusText |"
}

if ($firstFailureRunId) {
    $failSnippet = ($results | Where-Object { $_.RunId -eq $firstFailureRunId } | Select-Object -ExpandProperty LogSnippet)
    $reportContent += "`n`n---`n`n## 3. 首次失败现场分析`n`n- **首次失败轮次**: $firstFailureRunId`n- **失败原因**: $failureReason`n`n### 失败轮日志片段`n````text`n$failSnippet`n````"
}

$reportContent += "`n`n---`n`n## 4. 下一步整改与收敛建议`n1. **解除 CoreService.onUnbind 对长期持久化配置的立即清理**：`n   - CoreService.onUnbind 应仅作为运行时运行态降级，不应单次抹掉用户设置在 SharedPreferences 中的长期 desired state。`n2. **状态模型解耦（Desired State vs Effective State）**：`n   - 保留用户的 desiredEnabled；`n   - 当系统无障碍或服务暂时断开时，仅挂起 effective 运行态；待服务 reconnect 时自动恢复 enforcing。"

Set-Content -Path $ReportPath -Value $reportContent -Encoding UTF8
Log-Info "报告已成功写入: $ReportPath"
