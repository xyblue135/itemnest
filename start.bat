@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

set "PYTHON_CMD="
py -3 -c "import sys" >nul 2>&1 && set "PYTHON_CMD=py -3"
if not defined PYTHON_CMD python -c "import sys" >nul 2>&1 && set "PYTHON_CMD=python"

if not defined PYTHON_CMD (
  echo [ItemNest] 未在 PATH 中找到 Python 3。
  echo 请安装 Python 3 并勾选“Add Python to PATH”，然后重新运行 start.bat。
  pause
  exit /b 1
)

echo [ItemNest] 使用系统 Python: %PYTHON_CMD%

REM 核心依赖缺失才安装全部；aio_pika（RabbitMQ，可选）缺失时仅警告，不阻断启动
%PYTHON_CMD% -c "import fastapi, uvicorn, httpx, pydantic" >nul 2>&1
if errorlevel 1 (
  echo [ItemNest] 缺少核心依赖，正在安装 requirements.txt ...
  %PYTHON_CMD% -m pip install -r requirements.txt
  if errorlevel 1 (
    echo [ItemNest] 核心依赖安装失败。请手动执行：%PYTHON_CMD% -m pip install -r requirements.txt
    pause
    exit /b 1
  )
)

%PYTHON_CMD% -c "import aio_pika" >nul 2>&1
if errorlevel 1 (
  echo [ItemNest] 可选依赖 aio-pika（RabbitMQ 事件队列）缺失，尝试安装...
  %PYTHON_CMD% -m pip install aio-pika
  if errorlevel 1 (
    echo [ItemNest] 警告：aio-pika 安装失败。ItemNest 仍会正常运行，仅 RabbitMQ 事件功能不可用。
  ) else (
    echo [ItemNest] aio-pika 安装完成。
  )
) else (
  echo [ItemNest] aio-pika 已就绪。
)

start "ItemNest Browser" cmd /c "timeout /t 2 /nobreak >nul & start \"\" http://127.0.0.1:8765"
echo.
echo ItemNest 运行中: http://127.0.0.1:8765
echo 手机：用本机局域网 IP 加端口 8765，例如 http://192.168.3.100:8765
echo 按 Ctrl+C 停止。
%PYTHON_CMD% -m uvicorn app:app --host 0.0.0.0 --port 8765
