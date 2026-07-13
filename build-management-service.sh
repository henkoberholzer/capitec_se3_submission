#!/usr/bin/env bash

set -e
set -u
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"

docker run --rm \
  -v "$SCRIPT_DIR/code:/code" \
  -w /code/management-service \
  maven:3.9-eclipse-temurin-25 \
  /bin/bash -c "
    set -e
    keytool -importcert -noprompt -trustcacerts -alias zscaler-ca \
      -file /code/certs/zscaler-ca.pem -cacerts -storepass changeit 2>/dev/null || true
    mvn package -DskipTests
    chown -R ${HOST_UID}:${HOST_GID} /code/management-service/target 2>/dev/null || true
  "
