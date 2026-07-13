#!/usr/bin/env bash

# Run unit tests (and checkstyle via Maven validate) for both services.
# Usage: ./run-unit-tests.sh
#
# Matches the CI "unit-tests" job: both management-service and download-service.
# Docker runs as root for keytool; ownership of target/ is restored afterward
# so host `mvn` is not blocked by root-owned checkstyle/surefire files.

set -e
set -u
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"

run_service_tests() {
  local service="$1"
  echo "=== Unit tests: ${service} ==="
  docker run --rm \
    -v "$SCRIPT_DIR/code:/code" \
    -w "/code/${service}" \
    maven:3.9-eclipse-temurin-25 \
    /bin/bash -c "
      set -e
      keytool -importcert -noprompt -trustcacerts -alias zscaler-ca \
        -file /code/certs/zscaler-ca.pem -cacerts -storepass changeit 2>/dev/null || true
      mvn test
      # Leave target/ writable by the host user
      chown -R ${HOST_UID}:${HOST_GID} /code/${service}/target 2>/dev/null || true
    "
}

run_service_tests management-service
run_service_tests download-service

echo "All unit tests passed."
