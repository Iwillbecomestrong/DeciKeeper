#requires -Version 7
<#
.SYNOPSIS
    把 release_apks/ 里已构建、已校验的 APK 同步发布到 GitHub 与 Gitee 两个 Release。

.DESCRIPTION
    步骤：预检 -> 推 tag -> GitHub release -> Gitee release -> 逐个请求下载链接确认可用。
    幂等：release 已存在则只补传缺失的附件，已有附件不覆盖。
    -DryRun 只做预检并打印计划，不推送、不创建、不上传。

    前置：gh 已登录；环境变量 GITEE_TOKEN（需 projects 权限）；APK 已由 verify_release_artifact.ps1 生成 .sha256 与 manifest。
    令牌只从环境变量读取，不写入仓库、不打印。

.EXAMPLE
    pwsh scripts/publish_release.ps1 -Tag v0.4.2 -DryRun
    pwsh scripts/publish_release.ps1 -Tag v0.4.2
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^v\d+\.\d+\.\d+$')][string]$Tag,
    [string]$ReleaseDirectory,
    [ValidateSet('github', 'gitee')][string[]]$Platform = @('github', 'gitee'),
    [string]$GitHubRepo = 'Iwillbecomestrong/DeciKeeper',
    [string]$GiteeRepo = 'my_room_welcome_you/DeciKeeper',
    [switch]$Prerelease,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $ReleaseDirectory) { $ReleaseDirectory = Join-Path $repoRoot 'release_apks' }
$ReleaseDirectory = (Resolve-Path -LiteralPath $ReleaseDirectory).Path

function Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Fail($text) { throw "publish_release: $text" }
function Run($file, [string[]]$arguments) {
    $out = (& $file @arguments 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { Fail "命令失败 ($LASTEXITCODE): $file $($arguments -join ' ')`n$out" }
    return $out
}

# ---------- 1. 预检 ----------
Step "预检 $Tag"
$apk = Join-Path $ReleaseDirectory "DeciKeeper-$Tag-arm64.apk"
$shaFile = "$apk.sha256"
$manifestFile = Join-Path $ReleaseDirectory "DeciKeeper-$Tag-release-manifest.json"
$notesFile = Join-Path $ReleaseDirectory "$Tag-release-notes.md"
foreach ($f in @($apk, $shaFile, $manifestFile, $notesFile)) {
    if (-not (Test-Path -LiteralPath $f -PathType Leaf)) { Fail "缺少文件: $f" }
}

$apkHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$apkSize = (Get-Item -LiteralPath $apk).Length
$shaText = (Get-Content -LiteralPath $shaFile -Raw)
if ($shaText -notmatch $apkHash) { Fail ".sha256 文件与 APK 实际哈希不一致" }

$manifest = Get-Content -LiteralPath $manifestFile -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.artifact.sha256 -ne $apkHash) { Fail "manifest 的 sha256 与 APK 不一致，APK 可能被重新构建过" }
if ($manifest.artifact.versionName -ne $Tag.TrimStart('v')) { Fail "manifest versionName=$($manifest.artifact.versionName) 与 tag $Tag 不符" }
if (-not $manifest.signature.verified) { Fail "manifest 显示 APK 未通过签名校验" }

$tagCommit = Run 'git' @('-C', $repoRoot, 'rev-parse', "$Tag^{commit}")
if ($manifest.source.commit -ne $tagCommit) {
    Fail "APK 构建自 $($manifest.source.commit)，但 $Tag 指向 $tagCommit；请在 tag 对应提交上重新构建并重跑 verify_release_artifact.ps1"
}

$notes = Get-Content -LiteralPath $notesFile -Raw -Encoding UTF8
if ($notes -notmatch $apkHash) { Fail "release notes 里的 SHA-256 与 APK 不一致，请先更新 $notesFile" }

$assets = @($apk, $shaFile, $manifestFile)
$title = "决定守护 $Tag"
Write-Host "APK: $(Split-Path -Leaf $apk)  $apkSize 字节  sha256=$apkHash"
Write-Host "tag 提交: $tagCommit"

$giteeToken = $env:GITEE_TOKEN
if ('gitee' -in $Platform -and -not $giteeToken) { Fail "未设置环境变量 GITEE_TOKEN" }
if ('github' -in $Platform) { Run 'gh' @('auth', 'status') | Out-Null }

# ---------- 2. 推 tag ----------
foreach ($remote in $Platform) {
    Step "tag -> $remote"
    $remoteTag = Run 'git' @('-C', $repoRoot, 'ls-remote', '--tags', $remote, "refs/tags/$Tag^{}", "refs/tags/$Tag")
    if ($remoteTag) {
        Write-Host "$remote 已有 $Tag"
    } elseif ($DryRun) {
        Write-Host "[dry-run] 将推送 $Tag 到 $remote"
    } else {
        Run 'git' @('-C', $repoRoot, 'push', $remote, "refs/tags/$Tag") | Out-Null
        Write-Host "已推送 $Tag 到 $remote"
    }
}

# ---------- 3. GitHub ----------
if ('github' -in $Platform) {
    Step "GitHub release"
    $exists = $true
    $existing = & gh release view $Tag --repo $GitHubRepo --json assets 2>$null
    if ($LASTEXITCODE -ne 0) { $exists = $false }
    if (-not $exists) {
        if ($DryRun) {
            Write-Host "[dry-run] 将创建 GitHub release $title 并上传 $($assets.Count) 个附件"
        } else {
            $ghArgs = @('release', 'create', $Tag, '--repo', $GitHubRepo, '--title', $title, '--notes-file', $notesFile, '--verify-tag')
            if ($Prerelease) { $ghArgs += '--prerelease' }
            Run 'gh' ($ghArgs + $assets) | Out-Null
            Write-Host "已创建 GitHub release"
        }
    } else {
        $have = @(($existing | ConvertFrom-Json).assets.name)
        $missing = @($assets | Where-Object { (Split-Path -Leaf $_) -notin $have })
        $stray = @($have | Where-Object { $_ -notin ($assets | ForEach-Object { Split-Path -Leaf $_ }) })
        if ($stray) { Write-Warning "GitHub release 上有非本次命名的附件（需人工确认是否删除）: $($stray -join ', ')" }
        if (-not $missing) { Write-Host "GitHub release 附件已齐全" }
        elseif ($DryRun) { Write-Host "[dry-run] 将补传: $((($missing | ForEach-Object { Split-Path -Leaf $_ })) -join ', ')" }
        else { Run 'gh' (@('release', 'upload', $Tag, '--repo', $GitHubRepo) + $missing) | Out-Null; Write-Host "已补传 $($missing.Count) 个附件" }
    }
}

# ---------- 4. Gitee ----------
if ('gitee' -in $Platform) {
    Step "Gitee release"
    $api = "https://gitee.com/api/v5/repos/$GiteeRepo/releases"
    # 令牌放请求头：写入接口用 multipart 表单里的 access_token 字段会被判为登录失效
    $giteeAuth = @{ Authorization = "token $giteeToken" }
    $release = $null
    try { $release = Invoke-RestMethod -Uri "$api/tags/$Tag" -Method Get } catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 404) { throw }
    }
    if (-not $release) {
        if ($DryRun) {
            Write-Host "[dry-run] 将创建 Gitee release $title 并上传 $($assets.Count) 个附件"
        } else {
            $release = Invoke-RestMethod -Uri $api -Method Post -Headers $giteeAuth -ContentType 'application/x-www-form-urlencoded; charset=utf-8' -Body @{
                tag_name         = $Tag
                name             = $title
                body             = $notes
                prerelease       = if ($Prerelease) { 'true' } else { 'false' }
                target_commitish = $tagCommit
            }
            Write-Host "已创建 Gitee release (id=$($release.id))"
        }
    }
    if ($release) {
        $haveGitee = @(Invoke-RestMethod -Uri "$api/$($release.id)/attach_files?per_page=100" -Method Get | ForEach-Object { $_.name })
        foreach ($f in $assets) {
            $name = Split-Path -Leaf $f
            if ($name -in $haveGitee) { Write-Host "Gitee 已有 $name"; continue }
            if ($DryRun) { Write-Host "[dry-run] 将上传 $name"; continue }
            Invoke-RestMethod -Uri "$api/$($release.id)/attach_files" -Method Post -Headers $giteeAuth -Form @{
                file         = Get-Item -LiteralPath $f
            } | Out-Null
            Write-Host "已上传 $name"
        }
    }
}

# ---------- 5. 下载链接验证 ----------
if ($DryRun) { Write-Host "dry-run 结束，未做任何写入。"; exit 0 }
Step "验证下载链接"
$apkName = Split-Path -Leaf $apk
$urls = @{
    github = "https://github.com/$GitHubRepo/releases/download/$Tag/$apkName"
    gitee  = "https://gitee.com/$GiteeRepo/releases/download/$Tag/$apkName"
}
$failed = @()
foreach ($p in $Platform) {
    try {
        $r = Invoke-WebRequest -Uri $urls[$p] -Method Head -MaximumRedirection 5
        $len = [int64]($r.Headers['Content-Length'] | Select-Object -First 1)
        if ($len -ne $apkSize) { throw "大小 $len 与本地 $apkSize 不符" }
        Write-Host "[OK] $p $($urls[$p])"
    } catch {
        Write-Host "[FAIL] $p $($urls[$p]) -> $($_.Exception.Message)" -ForegroundColor Red
        $failed += $p
    }
}
if ($failed) { Fail "下载链接验证失败: $($failed -join ', ')" }
Write-Host "发布完成：$Tag 已在 $($Platform -join ' + ') 就绪。"
exit 0
