@echo off
setlocal
cd /d "%~dp0"
set "DEAD_EARTH_JAVA=java.exe"
if defined DEAD_EARTH_JAVA8 set "DEAD_EARTH_JAVA=%DEAD_EARTH_JAVA8%"
"%DEAD_EARTH_JAVA%" -version 2> "%TEMP%\dead-earth-java-version.txt"
findstr /c:"1.8." "%TEMP%\dead-earth-java-version.txt" >nul
if errorlevel 1 (
 echo Java 8 is required. Set DEAD_EARTH_JAVA8 to its java.exe.
 pause
 exit /b 2
)
"%DEAD_EARTH_JAVA%" -jar "%~dp0forge-1.12.2-14.23.5.2860-installer.jar"
if errorlevel 1 (
 echo Forge installer failed. Inspect the installer log in this folder.
 pause
 exit /b 2
)
