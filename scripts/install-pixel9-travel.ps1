param(
    [string]$DeviceSerial = "",
    [string]$BackendUrl = "",
    [switch]$BuildOnly,
    [switch]$SkipLaunch
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$TravelUrlPath = Join-Path $ProjectRoot "logs\travel-backend-url.txt"

if (-not $BackendUrl) {
    if (-not (Test-Path -LiteralPath $TravelUrlPath)) {
        throw "No travel backend URL found. Run scripts\run-backend-travel.ps1 first, or pass -BackendUrl https://..."
    }
    $BackendUrl = (Get-Content -LiteralPath $TravelUrlPath -Raw).Trim()
}

if (-not $BackendUrl.StartsWith("https://", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Travel setup requires a public HTTPS Backend URL. Got: $BackendUrl"
}

$installArgs = @{
    BackendUrl = $BackendUrl
    PreferCellularData = $true
}
if ($DeviceSerial) {
    $installArgs.DeviceSerial = $DeviceSerial
}
if ($BuildOnly) {
    $installArgs.BuildOnly = $true
}
if ($SkipLaunch) {
    $installArgs.SkipLaunch = $true
}

$installer = Join-Path $PSScriptRoot "install-pixel9-debug.ps1"
& $installer @installArgs
