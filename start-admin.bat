@echo off
setlocal
set "DEAD_EARTH_ACTION=%~1"
if "%DEAD_EARTH_ACTION%"=="" set "DEAD_EARTH_ACTION=start"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0director\admin.ps1" -Action "%DEAD_EARTH_ACTION%"
set "DEAD_EARTH_EXIT=%ERRORLEVEL%"
if not "%DEAD_EARTH_EXIT%"=="0" pause
exit /b %DEAD_EARTH_EXIT%
