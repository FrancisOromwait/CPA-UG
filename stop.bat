@echo off
setlocal
title Stop CPA Uganda
if "%PORT%"=="" set PORT=8082

echo.
echo Stopping the app...
powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort %PORT% -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }"
echo The app is stopped.
echo.

echo Note: MySQL may also be used by other systems on this computer.
choice /c YN /m "Also stop MySQL"
if errorlevel 2 (
  echo Leaving MySQL running.
) else (
  if exist "C:\xampp\mysql\bin\mysqladmin.exe" (
    "C:\xampp\mysql\bin\mysqladmin.exe" -u root shutdown
    echo MySQL is stopped.
  ) else (
    echo XAMPP was not found. MySQL was left running.
  )
)
echo.
pause
