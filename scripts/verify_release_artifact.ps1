[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,
    [string]$OutputDirectory,
    [string]$ExpectedPackage = "com.decikeeper.app",
    [string]$ExpectedVersionName,
    [string]$ExpectedTag,
    [string]$ExpectedCertificateSha256,
    [string]$TestStatus = "not-recorded",
    [switch]$RequireSigned
)

$ErrorActionPreference = "Stop"

function Invoke-CapturedCommand {
    param([string]$FilePath, [string[]]$Arguments)
    $output = (& $FilePath @Arguments 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed ($LASTEXITCODE): $FilePath $($Arguments -join ' ')`n$output"
    }
    return $output
}

function Find-SdkTool {
    param([string]$Name, [string]$SdkRoot, [string]$BuildTools)
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    # Windows 带 .exe/.bat 后缀；Linux/macOS 的 SDK 工具没有后缀。
    $candidates = @(
        (Join-Path $BuildTools "$Name.exe"),
        (Join-Path $BuildTools "$Name.bat"),
        (Join-Path $BuildTools $Name),
        (Join-Path $SdkRoot "cmdline-tools/latest/bin/$Name.bat"),
        (Join-Path $SdkRoot "cmdline-tools/latest/bin/$Name.exe"),
        (Join-Path $SdkRoot "cmdline-tools/latest/bin/$Name")
    )
    # 目录同名也会命中 Test-Path，只接受文件。
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    throw "Android SDK tool not found: $Name"
}

$apk = (Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop).Path
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Split-Path -Parent $apk
}
$outputDirResolve = Resolve-Path -LiteralPath $OutputDirectory -ErrorAction SilentlyContinue
$outputDir = if ($outputDirResolve) { $outputDirResolve.Path } else { $null }
if ([string]::IsNullOrWhiteSpace($outputDir)) {
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $outputDir = (Resolve-Path -LiteralPath $OutputDirectory).Path
}

$sdkRoot = $env:ANDROID_SDK_ROOT
if ([string]::IsNullOrWhiteSpace($sdkRoot)) { $sdkRoot = $env:ANDROID_HOME }
if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
    $localProperties = Join-Path $repoRoot "local.properties"
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($sdkLine) {
            # local.properties follows Java properties escaping: I\:\\AndordStudio -> I:\AndordStudio.
            $sdkRoot = ($sdkLine -replace '^sdk\.dir=', '').Replace('\:', ':').Replace('\\', '\')
        }
    }
}
if ([string]::IsNullOrWhiteSpace($sdkRoot)) { throw "ANDROID_SDK_ROOT/ANDROID_HOME or local.properties sdk.dir is required" }
$sdkRoot = (Resolve-Path -LiteralPath $sdkRoot -ErrorAction Stop).Path
$buildToolsDir = Get-ChildItem -LiteralPath (Join-Path $sdkRoot "build-tools") -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildToolsDir) { throw "No Android build-tools installation found under $sdkRoot" }

$aapt2 = Find-SdkTool "aapt2" $sdkRoot $buildToolsDir.FullName
$apksigner = Find-SdkTool "apksigner" $sdkRoot $buildToolsDir.FullName
$apkanalyzer = $null
try { $apkanalyzer = Find-SdkTool "apkanalyzer" $sdkRoot $buildToolsDir.FullName } catch { }

$badging = Invoke-CapturedCommand $aapt2 @("dump", "badging", $apk)
$packageMatch = [regex]::Match($badging, "package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'")
if (-not $packageMatch.Success) { throw "Unable to read package metadata from APK" }
$packageName = $packageMatch.Groups[1].Value
$versionCode = $packageMatch.Groups[2].Value
$versionName = $packageMatch.Groups[3].Value
if ($packageName -ne $ExpectedPackage) { throw "Unexpected package: $packageName (expected $ExpectedPackage)" }
if ($ExpectedVersionName -and $versionName -ne $ExpectedVersionName) {
    throw "Unexpected versionName: $versionName (expected $ExpectedVersionName)"
}
if ($ExpectedTag -and $ExpectedTag -ne "v$versionName" -and $ExpectedTag -ne $versionName) {
    throw "Tag/version mismatch: tag $ExpectedTag, versionName $versionName"
}
if ($badging -match "application-debuggable") { throw "Release APK is debuggable" }

if ($apkanalyzer) {
    $permissionText = Invoke-CapturedCommand $apkanalyzer @("manifest", "permissions", $apk)
} else {
    # build-tools ships aapt2 even when command-line tools (apkanalyzer) are absent.
    $permissionText = Invoke-CapturedCommand $aapt2 @("dump", "permissions", $apk)
}
$permissions = @([regex]::Matches($permissionText, 'android\.permission\.[A-Z0-9_]+') |
    ForEach-Object { $_.Value } | Sort-Object -Unique)
$forbiddenNetworkPermissions = @("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")
$unexpectedNetwork = @($permissions | Where-Object { $_ -in $forbiddenNetworkPermissions })
if ($unexpectedNetwork.Count -gt 0) { throw "Final APK declares forbidden network permissions: $($unexpectedNetwork -join ', ')" }

$signatureOutput = (& $apksigner verify --verbose --print-certs $apk 2>&1 | Out-String).Trim()
$signatureExitCode = $LASTEXITCODE
$verified = $signatureExitCode -eq 0 -and $signatureOutput -match "Verified using v[23] scheme.*: true"
if ($RequireSigned -and -not $verified) {
    throw "APK is not a valid signed release artifact; apksigner output:`n$signatureOutput"
}
$certificateMatch = [regex]::Match($signatureOutput, "(?im)certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)")
$certificateSha256 = if ($certificateMatch.Success) { $certificateMatch.Groups[1].Value.ToUpperInvariant() } else { $null }
if ($ExpectedCertificateSha256) {
    $expectedCertificate = $ExpectedCertificateSha256.Replace(" ", "").ToUpperInvariant()
    if ($certificateSha256 -ne $expectedCertificate) {
        throw "Certificate SHA-256 mismatch: $certificateSha256 (expected $expectedCertificate)"
    }
}

$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item -LiteralPath $apk).Length
$shaFile = Join-Path $outputDir ((Split-Path -Leaf $apk) + ".sha256")
"$hash  $(Split-Path -Leaf $apk)" | Set-Content -LiteralPath $shaFile -Encoding UTF8

$commit = (& git -C $repoRoot rev-parse HEAD 2>$null | Out-String).Trim()
$tag = (& git -C $repoRoot describe --tags --exact-match HEAD 2>$null | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($tag)) { $tag = $null }
$nativeCodeMatch = [regex]::Match($badging, "native-code:([^\r\n]+)")
$abi = if ($nativeCodeMatch.Success) { $nativeCodeMatch.Groups[1].Value.Trim() } else { "none" }
$relativeApk = [IO.Path]::GetRelativePath($repoRoot, $apk)
$manifest = [ordered]@{
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    source = [ordered]@{ commit = $commit; tag = $tag }
    artifact = [ordered]@{
        path = $relativeApk
        fileName = (Split-Path -Leaf $apk)
        sizeBytes = $size
        sha256 = $hash
        packageName = $packageName
        versionCode = $versionCode
        versionName = $versionName
        abi = $abi
    }
    permissions = $permissions
    signature = [ordered]@{ verified = $verified; certificateSha256 = $certificateSha256 }
    verification = [ordered]@{
        testStatus = $TestStatus
        apksignerExitCode = $signatureExitCode
        buildTools = $buildToolsDir.Name
        forbiddenNetworkPermissions = $forbiddenNetworkPermissions
    }
}
$manifestPath = Join-Path $outputDir "release-manifest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
Write-Output "Release artifact verified: $relativeApk"
Write-Output "SHA-256: $hash"
Write-Output "Manifest: $manifestPath"
# 校验失败均已 throw；这里清掉 apksigner（无签名包）/git describe（无 tag）遗留的非零退出码，
# 否则 CI 的 pwsh 步骤会因 $LASTEXITCODE 误判失败。
exit 0
