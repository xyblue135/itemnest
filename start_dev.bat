@echo off
setlocal
pushd "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
  echo [ItemNest] Java was not found. JDK 21 or newer is required.
  pause
  exit /b 1
)

set "JAVA_SPEC_VERSION="
for /f "tokens=3" %%V in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.specification.version ="') do set "JAVA_SPEC_VERSION=%%V"
if not defined JAVA_SPEC_VERSION (
  echo [ItemNest] Unable to detect the Java version.
  java -version
  pause
  exit /b 1
)
for /f "tokens=1 delims=." %%V in ("%JAVA_SPEC_VERSION%") do set "JAVA_MAJOR=%%V"
if %JAVA_MAJOR% LSS 21 (
  echo [ItemNest] Java %JAVA_SPEC_VERSION% detected. JDK 21 or newer is required.
  java -version
  pause
  exit /b 1
)
echo [ItemNest] Java %JAVA_SPEC_VERSION% detected.

set "PNPM_CMD="
where pnpm >nul 2>&1 && set "PNPM_CMD=pnpm"
if not defined PNPM_CMD (
  where corepack >nul 2>&1 && set "PNPM_CMD=corepack pnpm"
)
if not defined PNPM_CMD (
  echo [ItemNest] pnpm/corepack was not found.
  echo [ItemNest] Install Node.js and enable Corepack, or install pnpm.
  pause
  exit /b 1
)

rem Do not start a second backend on the same port. This also catches stale instances.
powershell -NoProfile -Command "try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', 8765); $c.Close(); exit 0 } catch { exit 1 }" >nul 2>&1
if not errorlevel 1 (
  echo [ItemNest] Port 8765 is already in use.
  echo [ItemNest] Stop the existing process first, then run start_dev.bat again.
  echo [ItemNest] You can inspect it with: netstat -ano ^| findstr :8765
  pause
  exit /b 1
)

set "ITEMNEST_DATA_DIR=%CD%\data"
set "SERVER_PORT=8765"
if not defined ITEMNEST_BIND_ADDRESS set "ITEMNEST_BIND_ADDRESS=127.0.0.1"

start "ItemNest Backend" cmd /k "cd /d ""%CD%\backend"" && set ""ITEMNEST_DATA_DIR=%ITEMNEST_DATA_DIR%"" && set ""SERVER_PORT=%SERVER_PORT%"" && set ""ITEMNEST_BIND_ADDRESS=%ITEMNEST_BIND_ADDRESS%"" && call mvnw.cmd spring-boot:run"

echo [ItemNest] Starting backend on http://127.0.0.1:8765 ...
echo [ItemNest] The first Maven run can take longer while dependencies are downloaded.

for /l %%I in (1,1,180) do (
  powershell -NoProfile -Command "try { $c = New-Object Net.Sockets.TcpClient; $a = $c.BeginConnect('127.0.0.1', 8765, $null, $null); if ($a.AsyncWaitHandle.WaitOne(300) -and $c.Connected) { $c.EndConnect($a); $c.Close(); exit 0 }; $c.Close(); exit 1 } catch { exit 1 }" >nul 2>&1
  if not errorlevel 1 goto backend_ready
  timeout /t 1 /nobreak >nul
)

echo.
echo [ItemNest] Backend did not become ready on port 8765.
echo [ItemNest] Check the "ItemNest Backend" window for the real Spring Boot / Maven error.
echo [ItemNest] Frontend was not started, so Vite will no longer spam ECONNREFUSED errors.
pause
exit /b 1

:backend_ready
echo [ItemNest] Backend is ready.

pushd frontend
if not exist node_modules (
  echo [ItemNest] Installing frontend dependencies...
  call %PNPM_CMD% install
  if errorlevel 1 (
    popd
    pause
    exit /b 1
  )
)

rem Backend is confirmed ready before the browser is opened.
start "" cmd /c "timeout /t 2 /nobreak >nul & start http://127.0.0.1:15473"
echo [ItemNest] Frontend: http://127.0.0.1:15473
echo [ItemNest] Backend:  http://127.0.0.1:8765
echo [ItemNest] Data:     %ITEMNEST_DATA_DIR%\inventory.db
echo [ItemNest] Press Ctrl+C here to stop Vite. Close the Backend window to stop Spring Boot.
call %PNPM_CMD% dev
popd
popd
