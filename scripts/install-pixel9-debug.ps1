param(
    [string]$DeviceSerial = "",
    [switch]$Lan,
    [string]$BackendUrl = "",
    [switch]$PreferCellularData,
    [switch]$BuildOnly,
    [switch]$SkipLaunch
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AndroidRoot = Join-Path $ProjectRoot "android"
$ApkPath = Join-Path $AndroidRoot "app\build\outputs\apk\debug\app-debug.apk"

function Get-LanBackendUrl {
    $address = Get-NetIPConfiguration |
        Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq "Up" } |
        ForEach-Object { $_.IPv4Address.IPAddress } |
        Where-Object { $_ -and $_ -notlike "169.254.*" } |
        Select-Object -First 1

    if (-not $address) {
        throw "No active LAN IPv4 address was found. Connect the PC to Wi-Fi/Ethernet or pass -BackendUrl manually."
    }

    return "http://${address}:8001"
}

function Get-BackendAppToken {
    $envPath = Join-Path $ProjectRoot "backend\.env"
    if (Test-Path -LiteralPath $envPath) {
        $tokenLine = Get-Content -LiteralPath $envPath |
            Where-Object { $_ -match "^\s*ALLOWED_APP_TOKEN\s*=" } |
            Select-Object -Last 1
        if ($tokenLine) {
            return (($tokenLine -split "=", 2)[1]).Trim().Trim('"').Trim("'")
        }
    }

    return "dev-local-token"
}

if (-not $BackendUrl) {
    $BackendUrl = if ($Lan) { Get-LanBackendUrl } else { "http://127.0.0.1:8001" }
}
$AppToken = Get-BackendAppToken
$PreferCellularValue = if ($PreferCellularData) { "true" } else { "false" }

$JavaCandidates = @(
    @(
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jre",
        $env:JAVA_HOME
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
)

if ($JavaCandidates.Count -gt 0) {
    $env:JAVA_HOME = $JavaCandidates[0]
    Write-Host "Using JAVA_HOME=$env:JAVA_HOME"
}
else {
    Write-Warning "Android Studio Java runtime was not found. Gradle will use java from PATH if available."
}

$SdkRoot = $env:ANDROID_HOME
if (-not $SdkRoot -or -not (Test-Path -LiteralPath $SdkRoot)) {
    $SdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}

$Adb = Join-Path $SdkRoot "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $Adb)) {
    throw "adb.exe was not found. Install Android SDK Platform Tools or check Android Studio SDK settings."
}

Write-Host "Building Duddy Translator debug APK for backend $BackendUrl..."
if ($PreferCellularData) {
    Write-Host "Prefer Cellular Data will default to ON for this build."
}
Push-Location -LiteralPath $AndroidRoot
try {
    & .\gradlew.bat :app:assembleDebug "-PDUDDY_BACKEND_URL=$BackendUrl" "-PDUDDY_APP_TOKEN=$AppToken" "-PDUDDY_PREFER_CELLULAR_DATA=$PreferCellularValue"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "Debug APK was not created at $ApkPath."
}

if ($BuildOnly) {
    Write-Host "Build complete: $ApkPath"
    return
}

try {
    Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri "$($BackendUrl.TrimEnd('/'))/health" | Out-Null
}
catch {
    Write-Warning "The backend did not answer at $($BackendUrl.TrimEnd('/'))/health. Start scripts\run-backend.ps1$(if ($Lan) { ' -Lan' } else { '' }) before translating."
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
if ($BackendUrl -eq "http://127.0.0.1:8001") {
    Write-Host "Forwarding Pixel 9 localhost:8001 to this PC..."
    & $Adb -s $DeviceSerial reverse tcp:8001 tcp:8001
    if ($LASTEXITCODE -ne 0) {
        throw "adb reverse failed with exit code $LASTEXITCODE."
    }
}
else {
    Write-Host "Backend URL baked into debug APK: $BackendUrl"
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

if ($BackendUrl.StartsWith("https://", [System.StringComparison]::OrdinalIgnoreCase)) {
    if ($BackendUrl.Contains(".trycloudflare.com")) {
        Write-Host "Pixel 9 quick-tunnel travel setup complete. Keep scripts\run-backend-travel.ps1 running while you translate."
    }
    else {
        Write-Host "Pixel 9 stable travel setup complete. The app is using the public HTTPS backend."
    }
}
else {
    Write-Host "Pixel 9 setup complete. Keep scripts\run-backend.ps1$(if ($Lan) { ' -Lan' } else { '' }) running while you translate."
}
