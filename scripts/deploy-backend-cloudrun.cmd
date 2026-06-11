@echo off
setlocal
cd /d "%~dp0.."
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\deploy-backend-cloudrun.ps1" %*
