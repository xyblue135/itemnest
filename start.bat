@echo off
setlocal
pushd "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
  echo [ItemNest] Java was not found. JDK 21 is required.
  pause
  exit /b 1
)

if not exist "backend\target\itemnest-0.8.0.jar" (
  echo [ItemNest] Application is not built yet. Running build.bat...
  call build.bat
  if errorlevel 1 exit /b 1
)

set "ITEMNEST_DATA_DIR=%CD%\data"
set "SERVER_PORT=8765"
if not defined ITEMNEST_BIND_ADDRESS set "ITEMNEST_BIND_ADDRESS=127.0.0.1"
start "" cmd /c "timeout /t 2 /nobreak >nul & start http://127.0.0.1:8765"

echo.
echo [ItemNest] URL: http://127.0.0.1:8765
echo [ItemNest] Data: %ITEMNEST_DATA_DIR%\inventory.db
echo [ItemNest] Bind: %ITEMNEST_BIND_ADDRESS%
echo [ItemNest] Press Ctrl+C to stop.
java -jar "backend\target\itemnest-0.8.0.jar"
popd
