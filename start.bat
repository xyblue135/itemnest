@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (
  echo [ItemNest] First run: creating Python environment...
  py -3 -m venv .venv 2>nul || python -m venv .venv
  call .venv\Scripts\activate.bat
  python -m pip install -U pip
  pip install -r requirements.txt
) else (
  call .venv\Scripts\activate.bat
)
start "ItemNest Browser" cmd /c "timeout /t 2 /nobreak >nul & start \"\" http://127.0.0.1:8765"
echo.
echo ItemNest is running: http://127.0.0.1:8765
echo Phone: use this PC's LAN IP with port 8765, e.g. http://192.168.3.100:8765
echo Press Ctrl+C to stop.
python -m uvicorn app:app --host 0.0.0.0 --port 8765
