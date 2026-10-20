@echo off
setlocal
if exist "%~dp0docker\START.bat" (
  call "%~dp0docker\START.bat" %*
) else (
  call "%~dp0legacy\docker\START.bat" %*
)
exit /b %ERRORLEVEL%
