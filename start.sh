#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1; then
  PYTHON=python
else
  echo '[ItemNest] 未在 PATH 中找到 Python 3。' >&2
  exit 1
fi

echo "[ItemNest] 使用系统 Python: $PYTHON"

if ! "$PYTHON" -c 'import fastapi, uvicorn, httpx, pydantic' >/dev/null 2>&1; then
  echo '[ItemNest] 缺少核心依赖，正在安装 requirements.txt ...'
  "$PYTHON" -m pip install -r requirements.txt
fi

if ! "$PYTHON" -c 'import aio_pika' >/dev/null 2>&1; then
  echo '[ItemNest] 可选依赖 aio-pika（RabbitMQ 事件队列）缺失，尝试安装...'
  if "$PYTHON" -m pip install aio-pika >/dev/null 2>&1; then
    echo '[ItemNest] aio-pika 安装完成。'
  else
    echo '[ItemNest] 警告：aio-pika 安装失败。ItemNest 仍会正常运行，仅 RabbitMQ 事件功能不可用。'
  fi
fi

exec "$PYTHON" -m uvicorn app:app --host 0.0.0.0 --port 8765
