$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$HostAddress = "127.0.0.1"
$AppPort = 5190
$BridgePort = 8791
$AppUrl = "http://${HostAddress}:$AppPort/"
$BridgeUrl = "http://${HostAddress}:$BridgePort/health"

function Test-Url {
    param([string]$Url)

    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 1
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 500
    }
    catch {
        return $false
    }
}

function Get-CommandOrThrow {
    param(
        [string[]]$Names,
        [string]$Message
    )

    foreach ($name in $Names) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) {
            return $command
        }
    }

    throw $Message
}

if (-not (Test-Path (Join-Path $ProjectRoot "node_modules"))) {
    $npmInstall = Get-CommandOrThrow `
        -Names @("npm.cmd", "npm.exe") `
        -Message "npm was not found on PATH. Install Node.js or run npm install manually."

    Start-Process `
        -FilePath "powershell.exe" `
        -ArgumentList @(
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            "Set-Location -LiteralPath '$ProjectRoot'; & '$($npmInstall.Source)' install"
        ) `
        -WorkingDirectory $ProjectRoot `
        -Wait `
        -WindowStyle Minimized
}

if (-not (Test-Url $BridgeUrl)) {
    $node = Get-CommandOrThrow `
        -Names @("node.exe", "node") `
        -Message "node was not found on PATH. Install Node.js or start the bridge manually."

    $bridgeScript = Join-Path $PSScriptRoot "translate-server.mjs"
    Start-Process `
        -FilePath "powershell.exe" `
        -ArgumentList @(
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            "Set-Location -LiteralPath '$ProjectRoot'; & '$($node.Source)' '$bridgeScript'"
        ) `
        -WorkingDirectory $ProjectRoot `
        -WindowStyle Hidden

    $deadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $deadline) {
        if (Test-Url $BridgeUrl) {
            break
        }
        Start-Sleep -Milliseconds 300
    }
}

if (-not (Test-Url $AppUrl)) {
    $npm = Get-CommandOrThrow `
        -Names @("npm.cmd", "npm.exe") `
        -Message "npm was not found on PATH. Install Node.js or start the app manually with npm run dev."

    Start-Process `
        -FilePath "powershell.exe" `
        -ArgumentList @(
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            "Set-Location -LiteralPath '$ProjectRoot'; & '$($npm.Source)' run dev"
        ) `
        -WorkingDirectory $ProjectRoot `
        -WindowStyle Hidden

    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline) {
        if (Test-Url $AppUrl) {
            break
        }
        Start-Sleep -Milliseconds 300
    }
}

Start-Process $AppUrl
