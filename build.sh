#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
(cd frontend && { [ -d node_modules ] || pnpm install; } && pnpm build)
(cd backend && mvn clean package)
