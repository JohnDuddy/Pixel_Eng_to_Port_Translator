param(
    [string]$DeviceSerial = "",
    [switch]$BuildOnly,
    [switch]$SkipLaunch
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AndroidRoot = Join-Path $ProjectRoot "android"
$ApkPath = Join-Path $AndroidRoot "app\build\outputs\apk\debug\app-debug.apk"
$AndroidStudioJbr = "C:\Program Files\Android\Android Studio\jbr"

if (Test-Path $AndroidStudioJbr) {
    $env:JAVA_HOME = $AndroidStudioJbr
}

$SdkRoot = $env:ANDROID_HOME
if (-not $SdkRoot -or -not (Test-Path $SdkRoot)) {
    $SdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}

$Adb = Join-Path $SdkRoot "platform-tools\adb.exe"
if (-not (Test-Path $Adb)) {
    throw "adb.exe was not found. Install Android SDK Platform Tools or check Android Studio SDK settings."
}

Write-Host "Building Duddy Translator debug APK..."
Push-Location -LiteralPath $AndroidRoot
try {
    & .\gradlew.bat :app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

if (-not (Test-Path $ApkPath)) {
    throw "Debug APK was not created at $ApkPath."
}

if ($BuildOnly) {
    Write-Host "Build complete: $ApkPath"
    return
}

try {
    Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri "http://127.0.0.1:8001/health" | Out-Null
}
catch {
    Write-Warning "The local backend did not answer at http://127.0.0.1:8001/health. Start it with scripts\run-backend.ps1 before translating."
}

$deviceLines = & $Adb devices
$connectedDevices = @(
    $deviceLines |
        Where-Object { $_ -match "`tdevice$" } |
        ForEach-Object { ($_ -split "`t")[0] }
)

if ($connectedDevices.Count -eq 0) {
    throw "No authorized Android device found. Connect your Pixel 9 by USB, enable USB debugging, and accept the authorization prompt."
}

if (-not $DeviceSerial) {
    $DeviceSerial = $connectedDevices[0]
}

if ($connectedDevices -notcontains $DeviceSerial) {
    throw "Device '$DeviceSerial' is not connected or authorized. Connected devices: $($connectedDevices -join ', ')"
}

$model = (& $Adb -s $DeviceSerial shell getprop ro.product.model).Trim()
$androidVersion = (& $Adb -s $DeviceSerial shell getprop ro.build.version.release).Trim()
$abi = (& $Adb -s $DeviceSerial shell getprop ro.product.cpu.abi).Trim()

if ($model -notmatch "Pixel 9") {
    Write-Warning "Connected model is '$model', not a Pixel 9. Continuing anyway."
}

Write-Host "Using device: $model, Android $androidVersion, ABI $abi"
Write-Host "Forwarding Pixel 9 localhost:8001 to this PC..."
& $Adb -s $DeviceSerial reverse tcp:8001 tcp:8001
if ($LASTEXITCODE -ne 0) {
    throw "adb reverse failed with exit code $LASTEXITCODE."
}

Write-Host "Installing $ApkPath..."
& $Adb -s $DeviceSerial install -r -d $ApkPath
if ($LASTEXITCODE -ne 0) {
    throw "APK install failed with exit code $LASTEXITCODE."
}

if (-not $SkipLaunch) {
    Write-Host "Launching Duddy Translator on Pixel 9..."
    & $Adb -s $DeviceSerial shell monkey -p com.duddylabs.translator.debug -c android.intent.category.LAUNCHER 1 | Out-Null
}

Write-Host "Pixel 9 setup complete. Keep scripts\run-backend.ps1 running while you translate."
