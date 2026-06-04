$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AndroidRoot = Join-Path $ProjectRoot "android"
$Studio = "C:\Program Files\Android\Android Studio\bin\studio64.exe"

if (-not (Test-Path $Studio)) {
    throw "Android Studio was not found at $Studio"
}

Start-Process -FilePath $Studio -ArgumentList "`"$AndroidRoot`""
