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
& $Python -m uvicorn app.main:app --host 127.0.0.1 --port 8001 --reload
