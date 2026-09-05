@echo off
rem Launch the dashboard (GUI app - no console window stays open).
start "" /D "%~dp0" webserver.exe --resources resources
exit
