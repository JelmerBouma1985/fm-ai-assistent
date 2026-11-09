@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\updater\windows-update-test.ps1" %*
set "testExitCode=%ERRORLEVEL%"
echo.
if not "%testExitCode%"=="0" echo Update test failed. See the error and log paths above.
pause
exit /b %testExitCode%
