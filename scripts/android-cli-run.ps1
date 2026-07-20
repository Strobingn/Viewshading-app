# Build + deploy Viewshade via Android CLI.
# Usage: .\scripts\android-cli-run.ps1 [-Device serial] [-SkipBuild]
param(
    [string]$Device = "",
    [switch]$SkipBuild
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$cliBin = Join-Path $env:USERPROFILE ".android\bin"
$platformTools = Join-Path $sdk "platform-tools"
$prefix = @()
if (Test-Path $cliBin) { $prefix += $cliBin }
if (Test-Path $platformTools) { $prefix += $platformTools }
if ($prefix.Count -gt 0) { $env:Path = (($prefix -join ";") + ";" + $env:Path) }

if (-not (Get-Command android -ErrorAction SilentlyContinue)) {
    Write-Error "android CLI not on PATH. Expected $cliBin (android.cmd). Open a new terminal after install."
    exit 1
}

if (-not $SkipBuild) {
    & "$PSScriptRoot\android-cli-build.ps1"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$apk = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) {
    Write-Error "Missing APK: $apk - run build first."
    exit 1
}

Write-Host "Devices:"
adb devices -l
$androidArgs = @("run", "--apks=$apk")
if ($Device) { $androidArgs += "--device=$Device" }
Write-Host ("android " + ($androidArgs -join " "))
& android @androidArgs
exit $LASTEXITCODE
