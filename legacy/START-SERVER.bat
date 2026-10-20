@echo off
setlocal
cd /d "%~dp0"
py -3 "%~dp0tools\legacy_server.py" start --root "%~dp0." %*
set "DEAD_EARTH_EXIT=%ERRORLEVEL%"
if not "%DEAD_EARTH_EXIT%"=="0" pause
exit /b %DEAD_EARTH_EXIT%
