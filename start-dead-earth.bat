@echo off
setlocal
set "DEAD_EARTH_SERVER=%~dp0..\Dead-Earth-Server-3.2.0"
if /i "%~1"=="stop" goto stop
if not "%~1"=="" if /i not "%~1"=="status" goto usage
if not exist "%DEAD_EARTH_SERVER%\run_local.py" goto missing
powershell.exe -NoProfile -Command "$ErrorActionPreference='Stop'; try { $root=(Resolve-Path -LiteralPath $env:DEAD_EARTH_SERVER).ProviderPath; $script=Join-Path $root 'run_local.py'; $running=@(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'python.exe' -and $_.CommandLine -and $_.CommandLine.Replace('/','\').IndexOf($script,[StringComparison]::OrdinalIgnoreCase) -ge 0 }); if($running.Count -gt 0){ Write-Host 'DEAD EARTH server and AI launcher are already running. Address:127.0.0.1:25565'; exit 10 }; $config=Get-Content -LiteralPath (Join-Path $root 'deployment.json') -Raw | ConvertFrom-Json; foreach($key in @('java','python','director_python')) { if(-not (Test-Path -LiteralPath $config.$key -PathType Leaf)){ throw ('Missing runtime: '+$key) } }; Write-Host 'DEAD EARTH server is stopped. Local environment is available.'; exit 0 } catch { Write-Host $_.Exception.Message; exit 1 }"
set "DEAD_EARTH_RESULT=%ERRORLEVEL%"
if "%DEAD_EARTH_RESULT%"=="10" exit /b 0
if not "%DEAD_EARTH_RESULT%"=="0" goto failed
if /i "%~1"=="status" exit /b 0
call "%DEAD_EARTH_SERVER%\Start-Server.bat"
exit /b %ERRORLEVEL%
:stop
if not exist "%DEAD_EARTH_SERVER%\Stop-Server.bat" goto missing
call "%DEAD_EARTH_SERVER%\Stop-Server.bat"
exit /b %ERRORLEVEL%
:missing
echo The configured local server folder was not found:
echo %DEAD_EARTH_SERVER%
echo Keep this launcher in the HBM-Bunker repository folder.
:failed
if /i not "%~1"=="status" pause
exit /b 1
:usage
echo Usage:start-dead-earth.bat [status^|stop]
exit /b 1