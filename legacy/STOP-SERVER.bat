@echo off
setlocal
if exist "%~dp0docker\STOP.bat" (
  call "%~dp0docker\STOP.bat" %*
) else (
  call "%~dp0legacy\docker\STOP.bat" %*
)
exit /b %ERRORLEVEL%
