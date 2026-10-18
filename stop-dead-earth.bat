@echo off
setlocal
call "%~dp0start-dead-earth.bat" stop
exit /b %ERRORLEVEL%
