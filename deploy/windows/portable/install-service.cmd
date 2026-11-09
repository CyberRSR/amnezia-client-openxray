@echo off
setlocal EnableExtensions

set "RC=0"
set "SERVICE_NAME=AmneziaVPN-service"
set "TUNNEL_SERVICE_NAME=AmneziaWGTunnel$AmneziaVPN"
set "REG_KEY=HKLM\SYSTEM\CurrentControlSet\Services\%SERVICE_NAME%"
set "SRC=%~dp0"
set "PAYLOAD=%SRC:~0,-1%"
set "DST=%~1"
if "%DST%"=="" set "DST=%ProgramFiles%\AmneziaVPN-WWG"
set "SERVICE_EXE=%DST%\AmneziaVPN-service.exe"
set "DATA_DIR=%ProgramData%\AmneziaVPN"
set "BACKUP=%DATA_DIR%\service-binPath.prev"

net session >nul 2>&1
if "%ERRORLEVEL%"=="0" goto :ready
echo Requesting administrator privileges...
if "%~1"=="" (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
) else (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -ArgumentList '%~1' -Verb RunAs"
)
exit /b 0

:ready
echo ======================================================================
echo  Amnezia VPN WWG - Windows service install
echo ======================================================================
echo   payload : %PAYLOAD%
echo   target  : %DST%
echo   service : %SERVICE_NAME%
echo.

if not exist "%SRC%AmneziaVPN-service.exe" goto :missing_payload
if not exist "%SRC%AmneziaVPN.exe" goto :missing_payload

echo Stopping running Amnezia VPN processes...
sc stop "%TUNNEL_SERVICE_NAME%" >nul 2>&1
sc delete "%TUNNEL_SERVICE_NAME%" >nul 2>&1
sc stop "%SERVICE_NAME%" >nul 2>&1
taskkill /F /IM AmneziaVPN-service.exe >nul 2>&1
taskkill /F /IM AmneziaVPN.exe >nul 2>&1
timeout /t 2 /nobreak >nul

set "PREV="
for /f "tokens=1,2,*" %%a in ('reg query "%REG_KEY%" /v ImagePath 2^>nul ^| findstr /i "ImagePath"') do set "PREV=%%c"
set "PREV=%PREV:"=%"

if /I "%PAYLOAD%"=="%DST%" (
    echo Files are already in the target directory, skipping the copy.
    goto :service
)

echo Copying files to "%DST%"...
robocopy "%PAYLOAD%" "%DST%" /E /NFL /NDL /NJH /NJS /R:2 /W:2 >nul
if %ERRORLEVEL% GEQ 8 goto :fail_copy

:service
reg query "%REG_KEY%" >nul 2>&1
if "%ERRORLEVEL%"=="0" goto :update_service

echo Creating the service...
sc create "%SERVICE_NAME%" type= own start= auto error= normal obj= LocalSystem DisplayName= "Amnezia VPN (WWG)" binPath= "\"%SERVICE_EXE%\"" depend= BFE/nsi
if not "%ERRORLEVEL%"=="0" goto :fail_service
goto :tune_service

:update_service
if "%PREV%"=="" goto :apply
if /I "%PREV%"=="%SERVICE_EXE%" goto :apply
if not exist "%DATA_DIR%" mkdir "%DATA_DIR%"
> "%BACKUP%" echo %PREV%
echo Previous service binary saved to "%BACKUP%"

:apply
echo Retargeting the service...
sc config "%SERVICE_NAME%" type= own start= auto error= normal obj= LocalSystem binPath= "\"%SERVICE_EXE%\"" depend= BFE/nsi
if not "%ERRORLEVEL%"=="0" goto :fail_service

:tune_service
sc description "%SERVICE_NAME%" "Manages the Amnezia VPN tunnel connections" >nul

echo Starting the service...
sc start "%SERVICE_NAME%" >nul 2>&1
set "TRIES=0"
:wait_loop
timeout /t 1 /nobreak >nul
sc query "%SERVICE_NAME%" 2>nul | findstr /C:"RUNNING" >nul && goto :running
set /a TRIES+=1
if %TRIES% LSS 15 goto :wait_loop
echo WARNING: the service has not reported RUNNING yet, check the log below.
goto :summary

:running
echo The service is running.

:summary
echo.
echo ----------------------------------------------------------------------
echo   installed   : %DST%
echo   app         : "%DST%\AmneziaVPN.exe"
echo   service log : %DATA_DIR%\log\AmneziaVPN-service.log
if exist "%BACKUP%" echo   previous    : %PREV%
if exist "%BACKUP%" echo               restored by uninstall-service.cmd
echo ----------------------------------------------------------------------
echo.
echo Start "%DST%\AmneziaVPN.exe" as a normal user and import the WWG profile.
echo Run uninstall-service.cmd to remove this service and re-register the
echo previous service binary path.
goto :end

:missing_payload
echo ERROR: AmneziaVPN.exe and AmneziaVPN-service.exe must sit next to this script.
echo ERROR: unpack the whole archive, then run install-service.cmd again.
set "RC=1"
goto :end

:fail_copy
echo ERROR: failed to copy the files to "%DST%".
echo ERROR: close Amnezia VPN and run this script again.
set "RC=1"
goto :end

:fail_service
echo ERROR: "sc" rejected the service registration.
echo ERROR: run install-service.cmd from an elevated command prompt.
set "RC=1"
goto :end

:end
echo.
pause
exit /b %RC%
