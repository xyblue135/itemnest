@echo off
setlocal
pushd "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
  echo [ItemNest] Java was not found. JDK 21 is required.
  pause
  exit /b 1
)

if not exist "backend\target\itemnest-0.7.0.jar" (
  echo [ItemNest] Application is not built yet. Running build.bat...
  call build.bat
  if errorlevel 1 exit /b 1
)

set "ITEMNEST_DATA_DIR=%CD%\data"
set "ITEMNEST_RABBITMQ_WORKER_ENABLED=true"
echo [ItemNest] Starting RabbitMQ worker...
java -jar "backend\target\itemnest-0.7.0.jar" --spring.main.web-application-type=none
popd
