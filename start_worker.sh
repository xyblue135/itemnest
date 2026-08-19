#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

if command -v python3 >/dev/null 2>&1; then PYTHON=python3
elif command -v python >/dev/null 2>&1; then PYTHON=python
else echo "[ItemNest Worker] Python 3 was not found."; exit 1
fi

if ! "$PYTHON" -c "import aio_pika" >/dev/null 2>&1; then
  echo "[ItemNest Worker] aio-pika is missing. Installing..."
  "$PYTHON" -m pip install aio_pika
fi

echo "[ItemNest Worker] using $PYTHON"
exec "$PYTHON" mq_worker.py
