@echo off
setlocal
cd /d "%~dp0"
echo ============================================================
echo   ADBlock Web Server  -  Windows 11 (x64)
echo   HTTP : http://localhost:8080/internal-stats
echo   HTTPS: https://localhost:8443/internal-test
echo   (press Ctrl+C to stop)
echo ============================================================
webserver.exe --resources resources
echo.
echo Server exited with code %errorlevel%.
pause
