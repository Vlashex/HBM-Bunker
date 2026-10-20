@echo off
setlocal
cd /d "%~dp0"
rem Use the official CLI: the per-user Docker Desktop.exe shortcut is broken.
docker desktop start --timeout 45
set "DEAD_EARTH_EXIT=%ERRORLEVEL%"
if not "%DEAD_EARTH_EXIT%"=="0" goto failed
docker --context desktop-linux info --format "{{.ServerVersion}}" >nul 2>nul
set "DEAD_EARTH_EXIT=%ERRORLEVEL%"
if not "%DEAD_EARTH_EXIT%"=="0" goto failed
rem Quiet validation resolves secrets locally without printing the configuration.
docker --context desktop-linux compose --env-file .env -f compose.yaml config --quiet
set "DEAD_EARTH_EXIT=%ERRORLEVEL%"
if not "%DEAD_EARTH_EXIT%"=="0" goto failed
docker --context desktop-linux compose --env-file .env -f compose.yaml up -d --build --wait --wait-timeout 240
set "DEAD_EARTH_EXIT=%ERRORLEVEL%"
if not "%DEAD_EARTH_EXIT%"=="0" goto failed
exit /b 0

:failed
echo DEAD EARTH stack could not start. No model weights are downloaded automatically.
pause
exit /b %DEAD_EARTH_EXIT%
