@echo off
setlocal EnableExtensions

set "RC=0"
set "SERVICE_NAME=AmneziaVPN-service"
set "TUNNEL_SERVICE_NAME=AmneziaWGTunnel$AmneziaVPN"
set "DATA_DIR=%ProgramData%\AmneziaVPN"
set "BACKUP=%DATA_DIR%\service-binPath.prev"

net session >nul 2>&1
if "%ERRORLEVEL%"=="0" goto :ready
echo Requesting administrator privileges...
powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
exit /b 0

:ready
echo ======================================================================
echo  Amnezia VPN WWG - Windows service removal
echo ======================================================================
echo.

echo Stopping running Amnezia VPN processes...
sc stop "%TUNNEL_SERVICE_NAME%" >nul 2>&1
sc delete "%TUNNEL_SERVICE_NAME%" >nul 2>&1
sc stop "%SERVICE_NAME%" >nul 2>&1
taskkill /F /IM AmneziaVPN-service.exe >nul 2>&1
taskkill /F /IM AmneziaVPN.exe >nul 2>&1
timeout /t 2 /nobreak >nul

sc delete "%SERVICE_NAME%" >nul 2>&1
if not "%ERRORLEVEL%"=="0" echo Note: "%SERVICE_NAME%" was not registered.
echo The service is removed.

if not exist "%BACKUP%" goto :done

set "PREV="
for /f "usebackq delims=" %%p in ("%BACKUP%") do set "PREV=%%p"
if "%PREV%"=="" goto :drop_backup
if not exist "%PREV%" (
    echo The previous service binary is gone, nothing to restore:
    echo   %PREV%
    goto :drop_backup
)

echo Restoring the previous service binary...
sc create "%SERVICE_NAME%" type= own start= auto error= normal obj= LocalSystem DisplayName= "Amnezia VPN" binPath= "\"%PREV%\"" depend= BFE/nsi >nul
if not "%ERRORLEVEL%"=="0" sc config "%SERVICE_NAME%" type= own start= auto error= normal obj= LocalSystem binPath= "\"%PREV%\"" depend= BFE/nsi >nul
sc description "%SERVICE_NAME%" "Manages the Amnezia VPN tunnel connections" >nul
sc start "%SERVICE_NAME%" >nul 2>&1
echo Restored: %PREV%

:drop_backup
del /F /Q "%BACKUP%" >nul 2>&1

:done
echo.
echo ----------------------------------------------------------------------
echo   application files were left untouched: %~dp0
echo   remove this folder yourself once it is not needed
echo ----------------------------------------------------------------------
goto :end

:end
echo.
pause
exit /b %RC%
