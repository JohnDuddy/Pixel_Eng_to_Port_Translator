param(
    [switch]$Lan,
    [string]$HostAddress = "",
    [int]$Port = 8001,
    [switch]$NoReload
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BackendRoot = Join-Path $ProjectRoot "backend"
$Venv = Join-Path $BackendRoot ".venv"
$Python = Join-Path $Venv "Scripts\python.exe"

if (-not (Test-Path $Python)) {
    Set-Location -LiteralPath $BackendRoot
    python -m venv .venv
    & $Python -m pip install --upgrade pip
    & $Python -m pip install -r requirements.txt
}

Set-Location -LiteralPath $BackendRoot

if (-not $HostAddress) {
    $HostAddress = if ($Lan) { "0.0.0.0" } else { "127.0.0.1" }
}

if ($Lan) {
    $lanAddresses = @(
        Get-NetIPConfiguration |
            Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq "Up" } |
            ForEach-Object { $_.IPv4Address.IPAddress } |
            Where-Object { $_ -and $_ -notlike "169.254.*" }
    )

    Write-Host ""
    Write-Host "LAN backend mode is enabled."
    Write-Host "Keep this PowerShell window open while translating."
    Write-Host "Set Duddy Translator Settings > Backend URL to one of these:"
    foreach ($address in $lanAddresses) {
        Write-Host "  http://${address}:$Port"
    }
    if ($lanAddresses.Count -eq 0) {
        Write-Warning "No active LAN IPv4 address was found. Check Wi-Fi/Ethernet and run ipconfig."
    }
    Write-Host ""
    Write-Warning "If Windows asks, allow Python/FastAPI through the Private network firewall."
    Write-Warning "Change backend\.env ALLOWED_APP_TOKEN before using this outside trusted local Wi-Fi."
    Write-Host ""
}
else {
    Write-Host "USB tunnel backend mode: http://127.0.0.1:$Port"
}

$uvicornArgs = @("app.main:app", "--host", $HostAddress, "--port", "$Port")
if (-not $NoReload) {
    $uvicornArgs += "--reload"
}

& $Python -m uvicorn @uvicornArgs
