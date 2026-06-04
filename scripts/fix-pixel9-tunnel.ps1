param(
    [string]$DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$SdkRoot = $env:ANDROID_HOME
if (-not $SdkRoot -or -not (Test-Path -LiteralPath $SdkRoot)) {
    $SdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}

$Adb = Join-Path $SdkRoot "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $Adb)) {
    throw "adb.exe was not found. Install Android SDK Platform Tools or check Android Studio SDK settings."
}

try {
    Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri "http://127.0.0.1:8001/health" | Out-Null
    Write-Host "Backend is reachable at http://127.0.0.1:8001/health."
}
catch {
    Write-Warning "The backend did not answer at http://127.0.0.1:8001/health. Start it with scripts\run-backend.ps1 before translating."
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
Write-Host "Using device: $model ($DeviceSerial)"
Write-Host "Forwarding Pixel 9 localhost:8001 to this PC..."
& $Adb -s $DeviceSerial reverse tcp:8001 tcp:8001
if ($LASTEXITCODE -ne 0) {
    throw "adb reverse failed with exit code $LASTEXITCODE."
}

Write-Host "Active reverse tunnels:"
& $Adb -s $DeviceSerial reverse --list
Write-Host "Pixel 9 tunnel repaired. Keep scripts\run-backend.ps1 running while you translate."
