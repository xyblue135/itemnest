@echo off
setlocal
pushd "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
  echo [ItemNest] Java was not found. JDK 21 is required.
  pause
  exit /b 1
)

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

set "ITEMNEST_DATA_DIR=%CD%\data"
set "SERVER_PORT=8765"
start "ItemNest Backend" cmd /k "cd /d ""%CD%\backend"" && set ""ITEMNEST_DATA_DIR=%ITEMNEST_DATA_DIR%"" && call mvnw.cmd spring-boot:run"

pushd frontend
if not exist node_modules (
  call %PNPM_CMD% install
  if errorlevel 1 (
    popd
    pause
    exit /b 1
  )
)
start "" cmd /c "timeout /t 3 /nobreak >nul & start http://127.0.0.1:15473"
echo [ItemNest] Frontend: http://127.0.0.1:15473
echo [ItemNest] Backend:  http://127.0.0.1:8765
call %PNPM_CMD% dev --host 0.0.0.0
popd
popd
