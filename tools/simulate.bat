@echo off
rem ============================================================
rem  Device simulator - no real hardware needed
rem  Usage: simulate.bat [server-ip] [port]
rem  NOTE: keep this file ASCII-only and CRLF.
rem ============================================================
set "DIR=%~dp0"
set "HOST=%~1"
set "PORT=%~2"
if "%HOST%"=="" set "HOST=127.0.0.1"
if "%PORT%"=="" set "PORT=9000"

where python >nul 2>nul
if errorlevel 1 (
  echo [ERROR] python not found. Please install Python 3 and add it to PATH.
  pause
  exit /b 1
)

python "%DIR%device-simulator.py" %HOST% %PORT%
pause
