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

if not exist "backend\target\itemnest-0.8.0.jar" (
  echo [ItemNest] Application is not built yet. Running build.bat...
  call build.bat
  if errorlevel 1 exit /b 1
)

powershell -NoProfile -Command "try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', 8765); $c.Close(); exit 0 } catch { exit 1 }" >nul 2>&1
if not errorlevel 1 (
  echo [ItemNest] Port 8765 is already in use.
  echo [ItemNest] Stop the existing process first, then run start.bat again.
  echo [ItemNest] You can inspect it with: netstat -ano ^| findstr :8765
  pause
  exit /b 1
)

set "ITEMNEST_DATA_DIR=%CD%\data"
set "SERVER_PORT=8765"
if not defined ITEMNEST_BIND_ADDRESS set "ITEMNEST_BIND_ADDRESS=127.0.0.1"

rem Open the browser only after the backend really starts listening.
start "" powershell -NoProfile -WindowStyle Hidden -Command "$deadline=(Get-Date).AddMinutes(3); while((Get-Date) -lt $deadline) { try { $c=New-Object Net.Sockets.TcpClient; $a=$c.BeginConnect('127.0.0.1',8765,$null,$null); if($a.AsyncWaitHandle.WaitOne(300) -and $c.Connected) { $c.EndConnect($a); $c.Close(); Start-Process 'http://127.0.0.1:8765'; exit 0 }; $c.Close() } catch {}; Start-Sleep -Milliseconds 700 }; exit 1"

echo.
echo [ItemNest] URL:  http://127.0.0.1:8765
echo [ItemNest] Data: %ITEMNEST_DATA_DIR%\inventory.db
echo [ItemNest] Bind: %ITEMNEST_BIND_ADDRESS%
echo [ItemNest] Java: %JAVA_SPEC_VERSION%
echo [ItemNest] Browser will open after the backend is ready.
echo [ItemNest] Press Ctrl+C to stop.
java -jar "backend\target\itemnest-0.8.0.jar"
popd
