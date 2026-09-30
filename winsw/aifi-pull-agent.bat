@echo off
setlocal
REM Keep config\, logs\ and spool\ next to the jar, whatever folder this was started from.
cd /d "%~dp0"

echo AIFI Pull Agent
echo.
echo   [1] Test all connections (DICOM Web Proxy, destinations)
echo   [2] Show the pull jobs waiting in the spool
echo   [3] Retry all waiting / failed pull jobs
echo   [4] Run in this window (Ctrl+C to stop)
echo   [5] Install and start as a Windows service   (requires admin)
echo   [6] Restart the Windows service              (requires admin)
echo   [7] Uninstall the Windows service            (requires admin)
echo   [8] Exit
echo.
set /p CHOICE="Enter choice (1-8): "

if "%CHOICE%"=="1" goto CHECK
if "%CHOICE%"=="2" goto STATUS
if "%CHOICE%"=="3" goto RETRY
if "%CHOICE%"=="4" goto RUN
if "%CHOICE%"=="5" goto INSTALL
if "%CHOICE%"=="6" goto RESTART
if "%CHOICE%"=="7" goto UNINSTALL
goto END

:CHECK
java -Dfile.encoding=UTF-8 -jar "%~dp0aifi-pull-agent.jar" check
pause
goto END

:STATUS
java -Dfile.encoding=UTF-8 -jar "%~dp0aifi-pull-agent.jar" status
pause
goto END

:RETRY
java -Dfile.encoding=UTF-8 -jar "%~dp0aifi-pull-agent.jar" retry all
pause
goto END

:RUN
java -Dfile.encoding=UTF-8 -jar "%~dp0aifi-pull-agent.jar" run
goto END

:INSTALL
call :REQUIRE_ADMIN || goto END
echo Service account: press Enter for LocalSystem, or type DOMAIN\user (recommended: a dedicated account)
set /p SVCUSER="Account: "
if "%SVCUSER%"=="" set "SVCUSER=LocalSystem"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-service.ps1" -InstallDir "%~dp0." -ServiceUser "%SVCUSER%"
pause
goto END

:RESTART
call :REQUIRE_ADMIN || goto END
"%~dp0aifi-pull-agent-service.exe" restart
pause
goto END

:UNINSTALL
call :REQUIRE_ADMIN || goto END
"%~dp0aifi-pull-agent-service.exe" stop
"%~dp0aifi-pull-agent-service.exe" uninstall
pause
goto END

:REQUIRE_ADMIN
net session >nul 2>&1
if errorlevel 1 (
  echo This option needs an elevated prompt: right-click the .bat and choose "Run as administrator".
  pause
  exit /b 1
)
exit /b 0

:END
endlocal
