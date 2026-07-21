@echo off
title Cobblify stats backend setup
echo Starting Cobblify stats backend setup...
echo.

set "PS1=%TEMP%\bedwarsqol-setup.ps1"

powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; try { Invoke-WebRequest -UseBasicParsing 'https://raw.githubusercontent.com/mrcobbert/bedwarsQOL/main/installers/setup-windows.ps1' -OutFile '%PS1%' } catch { Write-Host 'Could not download the setup helper. Check your internet connection.' -ForegroundColor Red; exit 1 }"
if errorlevel 1 goto end

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%"

:end
echo.
pause
