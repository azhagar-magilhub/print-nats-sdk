@echo off
rem Usage: install-service.cmd "<install dir>"   (run as Administrator)
cd /d "%~1\service"
PrintNatsSidecar.exe install
PrintNatsSidecar.exe start
