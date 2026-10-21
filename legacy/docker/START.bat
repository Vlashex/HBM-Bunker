@echo off
setlocal
cd /d "%~dp0"
set "DEAD_EARTH_DOCKER=docker"
if exist "%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin\docker.exe" set "DEAD_EARTH_DOCKER=%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin\docker.exe"
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0Start-Desktop.ps1" -Docker "%DEAD_EARTH_DOCKER%"
set "DEAD_EARTH_EXIT=%ERRORLEVEL%"
if not "%DEAD_EARTH_EXIT%"=="0" goto failed
"%DEAD_EARTH_DOCKER%" --context desktop-linux info --format "{{.ServerVersion}}" >nul 2>nul
set "DEAD_EARTH_EXIT=%ERRORLEVEL%"
if not "%DEAD_EARTH_EXIT%"=="0" goto failed
rem Quiet validation resolves secrets locally without printing the configuration.
"%DEAD_EARTH_DOCKER%" --context desktop-linux compose --env-file .env -f compose.yaml config --quiet
set "DEAD_EARTH_EXIT=%ERRORLEVEL%"
if not "%DEAD_EARTH_EXIT%"=="0" goto failed
"%DEAD_EARTH_DOCKER%" --context desktop-linux compose --env-file .env -f compose.yaml up -d --build --wait --wait-timeout 240
set "DEAD_EARTH_EXIT=%ERRORLEVEL%"
if not "%DEAD_EARTH_EXIT%"=="0" goto failed
exit /b 0

:failed
echo DEAD EARTH stack could not start. No model weights are downloaded automatically.
pause
exit /b %DEAD_EARTH_EXIT%
