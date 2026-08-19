@echo off
setlocal
pushd "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
  echo [ItemNest] Java was not found. JDK 21 is required.
  pause
  exit /b 1
)

for /f "tokens=2 delims=\"" %%V in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VERSION=%%V"
echo [ItemNest] Java: %JAVA_VERSION%

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

echo [1/2] Building Vue 3 frontend...
pushd frontend
if not exist node_modules (
  call %PNPM_CMD% install
  if errorlevel 1 (
    popd
    pause
    exit /b 1
  )
)
call %PNPM_CMD% build
if errorlevel 1 (
  popd
  pause
  exit /b 1
)
popd

echo [2/2] Building Spring Boot backend...
pushd backend
call mvnw.cmd clean package
if errorlevel 1 (
  popd
  pause
  exit /b 1
)
popd

echo.
echo [ItemNest] Build completed.
echo [ItemNest] JAR: backend\target\itemnest-0.8.0.jar
popd
