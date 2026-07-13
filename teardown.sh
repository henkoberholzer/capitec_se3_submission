#!/usr/bin/env bash
set -euo pipefail

echo "==> Tearing down existing app containers and volumes..."
docker compose down -v --remove-orphans
