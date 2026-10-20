@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0director\model.ps1" -Action pull -Backend auto
if errorlevel 1 pause
