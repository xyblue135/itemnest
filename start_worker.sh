#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
[ -f backend/target/itemnest-0.8.0.jar ] || ./build.sh
export ITEMNEST_DATA_DIR="$PWD/data"
export ITEMNEST_RABBITMQ_ENABLED=true
export ITEMNEST_RABBITMQ_WORKER_ENABLED=true
exec java -jar backend/target/itemnest-0.8.0.jar --spring.main.web-application-type=none
