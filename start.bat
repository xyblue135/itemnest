@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

set "PYTHON_CMD="
py -3 -c "import sys" >nul 2>&1 && set "PYTHON_CMD=py -3"
if not defined PYTHON_CMD python -c "import sys" >nul 2>&1 && set "PYTHON_CMD=python"

if not defined PYTHON_CMD (
  echo [ItemNest] Python 3 was not found in PATH.
  echo Please install Python 3 and enable "Add Python to PATH", then run start.bat again.
  pause
  exit /b 1
)

echo [ItemNest] Using system Python: %PYTHON_CMD%
%PYTHON_CMD% -c "import fastapi, uvicorn, httpx, pydantic" >nul 2>&1
if errorlevel 1 (
  echo [ItemNest] Missing dependencies. Installing into the system Python environment...
  %PYTHON_CMD% -m pip install -r requirements.txt
  if errorlevel 1 (
    echo [ItemNest] Dependency installation failed.
    echo Try manually: %PYTHON_CMD% -m pip install -r requirements.txt
    pause
    exit /b 1
  )
)

start "ItemNest Browser" cmd /c "timeout /t 2 /nobreak >nul & start \"\" http://127.0.0.1:8765"
echo.
echo ItemNest is running: http://127.0.0.1:8765
echo Phone: use this PC's LAN IP with port 8765, e.g. http://192.168.3.100:8765
echo Press Ctrl+C to stop.
%PYTHON_CMD% -m uvicorn app:app --host 0.0.0.0 --port 8765
