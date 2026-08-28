@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-TimeStamp.ps1"
if errorlevel 1 (
  echo.
  echo TimeStamp installation failed. Keep this window open for support.
  pause
  exit /b 1
)
pause
