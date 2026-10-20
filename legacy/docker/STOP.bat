@echo off
setlocal
cd /d "%~dp0"
docker --context desktop-linux compose --env-file .env -f compose.yaml down
set "DEAD_EARTH_EXIT=%ERRORLEVEL%"
if not "%DEAD_EARTH_EXIT%"=="0" pause
exit /b %DEAD_EARTH_EXIT%
