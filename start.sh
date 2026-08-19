#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
[ -f backend/target/itemnest-0.8.0.jar ] || ./build.sh
export ITEMNEST_DATA_DIR="$PWD/data"
export SERVER_PORT="${SERVER_PORT:-8765}"
export ITEMNEST_BIND_ADDRESS="${ITEMNEST_BIND_ADDRESS:-127.0.0.1}"
(sleep 2; command -v xdg-open >/dev/null && xdg-open http://127.0.0.1:8765 >/dev/null 2>&1 || true) &
exec java -jar backend/target/itemnest-0.8.0.jar
