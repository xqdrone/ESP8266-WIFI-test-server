@echo off
rem ============================================================
rem  ESP8266 WiFi / TCP test server - start script
rem
rem  All settings are at the top of this file.
rem  NOTE: keep this file ASCII-only and CRLF, cmd.exe needs CRLF.
rem ============================================================

rem ---------- editable settings ----------
set "WEB_PORT=8080"
set "TCP_PORT=9000"
set "HEARTBEAT_MS=15000"
rem ----------------------------------------

setlocal
set "DIR=%~dp0"
cd /d "%DIR%"

where mvn >nul 2>nul
if errorlevel 1 (
  echo [ERROR] mvn not found. Please install Maven and add it to PATH.
  pause
  exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] java not found. Please install JDK 17+ and add it to PATH.
  pause
  exit /b 1
)

echo [1/3] Compiling...
call mvn -o -B -q compile
if errorlevel 1 (
  echo.
  echo [ERROR] Compile failed.
  echo         If this machine has internet access, remove -o below and retry:
  echo             call mvn -B -q compile
  pause
  exit /b 1
)

rem Dependencies go to target\lib so the classpath can use the lib\* wildcard.
rem Building a literal classpath string from classpath.txt does NOT work: the file
rem is a single ~3.6 KB line, and cmd's "set /p" silently truncates it at about
rem 1023 characters, which drops half of the Spring jars.
echo [2/3] Copying runtime dependencies to target\lib ...
call mvn -o -B -q dependency:copy-dependencies -DoutputDirectory="target\lib" -DincludeScope=runtime
if errorlevel 1 (
  echo [ERROR] Dependency resolution failed.
  pause
  exit /b 1
)

echo [3/3] Starting server...
echo.
echo   Web console : http://127.0.0.1:%WEB_PORT%
echo   Device TCP  : ^<this PC LAN IP^>:%TCP_PORT%   ^(available IPs are printed below^)
echo   Stop        : press Ctrl+C in this window
echo.

java -cp "target\classes;target\lib\*" ^
     -Dserver.port=%WEB_PORT% ^
     -Dapp.tcp.port=%TCP_PORT% ^
     -Dapp.heartbeat-timeout-millis=%HEARTBEAT_MS% ^
     com.example.esp8266.Esp8266TcpServerApplication

echo.
echo Server stopped.
pause
endlocal
