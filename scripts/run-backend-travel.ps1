param(
    [int]$Port = 8001,
    [string]$CloudflaredPath = "",
    [switch]$NoInstallCloudflared
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BackendRoot = Join-Path $ProjectRoot "backend"
$EnvPath = Join-Path $BackendRoot ".env"
$LogsRoot = Join-Path $ProjectRoot "logs"
$ToolsRoot = Join-Path $ProjectRoot ".tools\cloudflared"
$Python = Join-Path $BackendRoot ".venv\Scripts\python.exe"
$BackendOutLog = Join-Path $LogsRoot "travel-backend.out.log"
$BackendErrLog = Join-Path $LogsRoot "travel-backend.err.log"
$TunnelOutLog = Join-Path $LogsRoot "cloudflared-travel.out.log"
$TunnelErrLog = Join-Path $LogsRoot "cloudflared-travel.err.log"
$TravelUrlPath = Join-Path $LogsRoot "travel-backend-url.txt"

function New-TravelSecret {
    param([int]$Bytes = 32)
    $buffer = [byte[]]::new($Bytes)
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($buffer)
    }
    finally {
        $rng.Dispose()
    }
    [Convert]::ToBase64String($buffer).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function Get-EnvFileValue {
    param([string]$Name)
    if (-not (Test-Path -LiteralPath $EnvPath)) {
        return ""
    }
    $line = Get-Content -LiteralPath $EnvPath |
        Where-Object { $_ -match "^\s*$([regex]::Escape($Name))\s*=" } |
        Select-Object -Last 1
    if (-not $line) {
        return ""
    }
    return (($line -split "=", 2)[1]).Trim().Trim('"').Trim("'")
}

function Set-EnvFileValue {
    param(
        [string]$Name,
        [string]$Value
    )
    $assignment = "$Name=$Value"
    if (-not (Test-Path -LiteralPath $EnvPath)) {
        Set-Content -LiteralPath $EnvPath -Value $assignment
        return
    }

    $pattern = "^\s*$([regex]::Escape($Name))\s*="
    $found = $false
    $updated = Get-Content -LiteralPath $EnvPath | ForEach-Object {
        if ($_ -match $pattern) {
            $found = $true
            $assignment
        }
        else {
            $_
        }
    }
    if (-not $found) {
        $updated += $assignment
    }
    Set-Content -LiteralPath $EnvPath -Value $updated
}

function Ensure-TravelEnvironment {
    if (-not (Test-Path -LiteralPath $EnvPath)) {
        Copy-Item -LiteralPath (Join-Path $BackendRoot ".env.example") -Destination $EnvPath
    }

    $openAiKey = Get-EnvFileValue "OPENAI_API_KEY"
    if (-not $openAiKey -or $openAiKey -eq "your_openai_api_key_here") {
        throw "backend\.env needs OPENAI_API_KEY before travel mode can run."
    }

    $appToken = Get-EnvFileValue "ALLOWED_APP_TOKEN"
    if (-not $appToken -or $appToken -eq "dev-local-token") {
        Set-EnvFileValue "ALLOWED_APP_TOKEN" (New-TravelSecret)
        Write-Host "Generated a travel-safe ALLOWED_APP_TOKEN in backend\.env."
    }

    $sessionSecret = Get-EnvFileValue "SESSION_TOKEN_SECRET"
    if (-not $sessionSecret -or $sessionSecret -eq "replace_with_a_long_random_secret" -or $sessionSecret -eq $appToken) {
        Set-EnvFileValue "SESSION_TOKEN_SECRET" (New-TravelSecret)
        Write-Host "Generated a travel-safe SESSION_TOKEN_SECRET in backend\.env."
    }
}

function Ensure-PythonEnvironment {
    if (-not (Test-Path -LiteralPath $Python)) {
        Push-Location -LiteralPath $BackendRoot
        try {
            python -m venv .venv
            & $Python -m pip install --upgrade pip
            & $Python -m pip install -r requirements.txt
        }
        finally {
            Pop-Location
        }
    }
}

function Get-Cloudflared {
    if ($CloudflaredPath -and (Test-Path -LiteralPath $CloudflaredPath)) {
        return $CloudflaredPath
    }

    $command = Get-Command cloudflared -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $localPath = Join-Path $ToolsRoot "cloudflared.exe"
    if (Test-Path -LiteralPath $localPath) {
        return $localPath
    }

    if ($NoInstallCloudflared) {
        throw "cloudflared.exe was not found. Install Cloudflare Tunnel or rerun without -NoInstallCloudflared."
    }

    New-Item -ItemType Directory -Force -Path $ToolsRoot | Out-Null
    Write-Host "Downloading cloudflared for the public HTTPS travel tunnel..."
    $downloadUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
    curl.exe -L --fail --output $localPath $downloadUrl
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $localPath)) {
        throw "cloudflared download failed."
    }
    return $localPath
}

function Wait-ForBackendHealth {
    $healthUrl = "http://127.0.0.1:$Port/health"
    for ($attempt = 1; $attempt -le 40; $attempt += 1) {
        try {
            Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri $healthUrl | Out-Null
            return
        }
        catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "Backend did not answer at $healthUrl. See $BackendErrLog."
}

function Wait-ForTunnelUrl {
    $pattern = "https://[-a-zA-Z0-9]+\.trycloudflare\.com"
    for ($attempt = 1; $attempt -le 90; $attempt += 1) {
        $text = ""
        foreach ($path in @($TunnelOutLog, $TunnelErrLog)) {
            if (Test-Path -LiteralPath $path) {
                $text += "`n" + (Get-Content -LiteralPath $path -Raw)
            }
        }
        $match = [regex]::Match($text, $pattern)
        if ($match.Success) {
            return $match.Value.TrimEnd("/")
        }
        Start-Sleep -Seconds 1
    }
    throw "Cloudflare tunnel did not produce a public URL. See $TunnelErrLog."
}

New-Item -ItemType Directory -Force -Path $LogsRoot | Out-Null
Remove-Item -LiteralPath $BackendOutLog, $BackendErrLog, $TunnelOutLog, $TunnelErrLog, $TravelUrlPath -Force -ErrorAction SilentlyContinue

Ensure-TravelEnvironment
Ensure-PythonEnvironment
$Cloudflared = Get-Cloudflared

$backendArgs = @("-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "$Port")
$backendProcess = Start-Process -FilePath $Python -ArgumentList $backendArgs -WorkingDirectory $BackendRoot -RedirectStandardOutput $BackendOutLog -RedirectStandardError $BackendErrLog -PassThru -WindowStyle Hidden

try {
    Wait-ForBackendHealth

    $tunnelArgs = @("tunnel", "--url", "http://127.0.0.1:$Port", "--no-autoupdate")
    $tunnelProcess = Start-Process -FilePath $Cloudflared -ArgumentList $tunnelArgs -WorkingDirectory $ProjectRoot -RedirectStandardOutput $TunnelOutLog -RedirectStandardError $TunnelErrLog -PassThru -WindowStyle Hidden

    $travelUrl = Wait-ForTunnelUrl
    Set-Content -LiteralPath $TravelUrlPath -Value $travelUrl

    Write-Host ""
    Write-Host "Duddy Translator travel backend is public at:"
    Write-Host "  $travelUrl"
    Write-Host ""
    Write-Host "Install the Pixel 9 travel build in another PowerShell window with:"
    Write-Host "  cd C:\dev\DuddyTranslator"
    Write-Host "  .\scripts\install-pixel9-travel.ps1"
    Write-Host ""
    Write-Host "Keep this window open while translating over 5G. Press Ctrl+C to stop."
    Write-Host ""

    while ($true) {
        if ($backendProcess.HasExited) {
            throw "Backend stopped. See $BackendErrLog."
        }
        if ($tunnelProcess.HasExited) {
            throw "Cloudflare tunnel stopped. See $TunnelErrLog."
        }
        Start-Sleep -Seconds 2
    }
}
finally {
    if ($tunnelProcess -and -not $tunnelProcess.HasExited) {
        Stop-Process -Id $tunnelProcess.Id -Force
    }
    if ($backendProcess -and -not $backendProcess.HasExited) {
        Stop-Process -Id $backendProcess.Id -Force
    }
}
