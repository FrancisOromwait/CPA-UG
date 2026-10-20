@echo off
setlocal
title CPA Uganda
cd /d "%~dp0"
if "%PORT%"=="" set PORT=8082

echo.
echo  CPA Uganda
echo  ---------------------
echo.

rem ---- 1. MySQL (XAMPP) ----
powershell -NoProfile -Command "exit ([int](-not (Test-NetConnection localhost -Port 3306 -WarningAction SilentlyContinue).TcpTestSucceeded))"
if errorlevel 1 (
  if not exist "C:\xampp\mysql\bin\mysqld.exe" (
    echo MySQL is not running and XAMPP was not found in C:\xampp.
    echo Start MySQL yourself, then run this again.
    pause
    exit /b 1
  )
  echo Starting MySQL...
  powershell -NoProfile -Command "Start-Process 'C:\xampp\mysql\bin\mysqld.exe' -ArgumentList '--defaults-file=C:\xampp\mysql\bin\my.ini','--standalone' -WindowStyle Hidden"
  powershell -NoProfile -Command "for($i=0;$i -lt 30;$i++){ if((Test-NetConnection localhost -Port 3306 -WarningAction SilentlyContinue).TcpTestSucceeded){exit 0}; Start-Sleep 2 }; exit 1"
  if errorlevel 1 (
    echo MySQL did not start. Open the XAMPP Control Panel and start MySQL there.
    pause
    exit /b 1
  )
) else (
  echo MySQL is running.
)

rem ---- 2. The app ----
powershell -NoProfile -Command "exit ([int](-not [bool](Get-NetTCPConnection -LocalPort %PORT% -State Listen -ErrorAction SilentlyContinue)))"
if not errorlevel 1 (
  echo The app is already running. Opening it in your browser.
  start "" "http://localhost:%PORT%/"
  exit /b 0
)

echo Starting the app. This can take a minute or two.
echo Your browser opens by itself when it is ready.
echo.
echo Keep this window open while you use the system.
echo To stop the app, close this window or press Ctrl+C.
echo.

start "" /b powershell -NoProfile -WindowStyle Hidden -Command "for($i=0;$i -lt 120;$i++){ try { Invoke-WebRequest 'http://localhost:%PORT%/' -UseBasicParsing -TimeoutSec 2 | Out-Null; Start-Process 'http://localhost:%PORT%/'; break } catch { Start-Sleep 3 } }"

call mvn -B clean spring-boot:run

echo.
echo The app has stopped.
pause
