@echo off
setlocal
pushd "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
  echo [ItemNest] Java was not found. JDK 21 is required.
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
echo [ItemNest] Java: %JAVA_SPEC_VERSION%

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
