$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Desktop = [Environment]::GetFolderPath("Desktop")
$ShortcutPath = Join-Path $Desktop "Duddy Translator Android.lnk"
$LaunchScript = Join-Path $PSScriptRoot "open-android-studio.ps1"
$PowerShell = Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe"

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($ShortcutPath)
$shortcut.TargetPath = $PowerShell
$shortcut.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$LaunchScript`""
$shortcut.WorkingDirectory = $ProjectRoot
$shortcut.IconLocation = "C:\Program Files\Android\Android Studio\bin\studio64.exe,0"
$shortcut.Description = "Open Duddy Translator Android Studio project"
$shortcut.Save()

Write-Host "Created desktop shortcut: $ShortcutPath"
