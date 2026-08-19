@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

set "PYTHON_CMD="
py -3 -c "import sys" >nul 2>&1 && set "PYTHON_CMD=py -3"
if not defined PYTHON_CMD python -c "import sys" >nul 2>&1 && set "PYTHON_CMD=python"

if not defined PYTHON_CMD (
  echo [ItemNest Worker] 未在 PATH 中找到 Python 3。
  pause
  exit /b 1
)

%PYTHON_CMD% -c "import aio_pika" >nul 2>&1
if errorlevel 1 (
  echo [ItemNest Worker] 缺少 aio-pika，正在安装...
  %PYTHON_CMD% -m pip install aio-pika
  if errorlevel 1 (
    echo [ItemNest Worker] aio-pika 安装失败，无法运行 Worker。
    pause
    exit /b 1
  )
)

echo [ItemNest Worker] 使用系统 Python: %PYTHON_CMD%
%PYTHON_CMD% mq_worker.py
