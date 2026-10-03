param(
    [string]$Apk,
    [string]$Serial,
    [string]$Adb
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Apk)) {
    $Apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
}
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
    throw 'APK not found. Run Build Android App.cmd first or pass -Apk with a built APK path.'
}
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
if ([System.IO.Path]::GetExtension($apkPath) -ne '.apk') {
    throw 'Choose an APK for local installation. An AAB is uploaded through Google Play.'
}
if ([string]::IsNullOrWhiteSpace($Adb)) {
    $adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($adbCommand) { $Adb = $adbCommand.Source }
    else {
        $candidates = @()
        if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
        if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
        if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe') }
        $Adb = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    }
}
if ([string]::IsNullOrWhiteSpace($Adb) -or -not (Test-Path -LiteralPath $Adb -PathType Leaf)) {
    throw 'Windows ADB was not found. Install Android SDK Platform-Tools, or pass -Adb with the full adb.exe path. For WSL ADB, use the WSL instructions in README.md.'
}
$devicesOutput = & $Adb devices
if ($LASTEXITCODE -ne 0) { throw 'ADB could not list devices.' }
$connected = @($devicesOutput | Where-Object { $_ -match '^\S+\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
if ([string]::IsNullOrWhiteSpace($Serial)) {
    if ($connected.Count -eq 0) {
        $devicesOutput | Write-Host
        throw 'No authorized phone or emulator. Enable USB debugging, connect the phone, and approve its authorization prompt.'
    }
    if ($connected.Count -gt 1) {
        $devicesOutput | Write-Host
        throw 'More than one device is connected. Run this script with -Serial and the intended device serial.'
    }
    $Serial = $connected[0]
}
if ($Serial -notin $connected) { throw 'The selected device is not connected and authorized.' }
Write-Host 'Installing the APK while preserving existing app data...'
& $Adb -s $Serial install -r $apkPath
if ($LASTEXITCODE -ne 0) {
    throw 'Installation failed. Read the device/ADB message. This script will not uninstall the existing app or erase its documents.'
}
Write-Host 'Installed. Open PDFix on the device.'
