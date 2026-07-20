# Build Viewshade debug APK using Gradle wrapper.
# Usage: .\scripts\android-cli-build.ps1
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

Write-Host "SDK: $sdk"
Write-Host "Building :app:assembleDebug ..."
& .\gradlew.bat :app:assembleDebug --stacktrace
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) {
    Write-Host "OK: $apk"
} else {
    Write-Error "APK not found at $apk"
    exit 1
}
