param(
    [string]$DeviceSerial = "",
    [string]$BackendUrl = "",
    [switch]$BuildOnly,
    [switch]$SkipLaunch
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$CloudRunUrlPath = Join-Path $ProjectRoot "logs\cloudrun-backend-url.txt"

if (-not $BackendUrl) {
    if (-not (Test-Path -LiteralPath $CloudRunUrlPath)) {
        throw "No Cloud Run backend URL found. Run scripts\deploy-backend-cloudrun.ps1 first, or pass -BackendUrl https://..."
    }
    $BackendUrl = (Get-Content -LiteralPath $CloudRunUrlPath -Raw).Trim()
}

if (-not $BackendUrl.StartsWith("https://", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Cloud Run travel setup requires a public HTTPS Backend URL. Got: $BackendUrl"
}

$installer = Join-Path $PSScriptRoot "install-pixel9-travel.ps1"
$installArgs = @{
    BackendUrl = $BackendUrl
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

& $installer @installArgs
