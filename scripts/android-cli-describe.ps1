# Refresh Android CLI project metadata for Viewshade.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
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

android describe --project_dir=$Root
exit $LASTEXITCODE
