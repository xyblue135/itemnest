#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1; then
  PYTHON=python
else
  echo '[ItemNest] Python 3 was not found in PATH.' >&2
  exit 1
fi

echo "[ItemNest] Using system Python: $PYTHON"
if ! "$PYTHON" -c 'import fastapi, uvicorn, httpx, pydantic' >/dev/null 2>&1; then
  echo '[ItemNest] Missing dependencies. Installing into the system Python environment...'
  "$PYTHON" -m pip install -r requirements.txt
fi

exec "$PYTHON" -m uvicorn app:app --host 0.0.0.0 --port 8765
