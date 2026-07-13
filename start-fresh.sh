#!/usr/bin/env bash

# Scrubs the environment clean, builds all services, and starts the full stack.
# Run this for a guaranteed clean slate before testing.

set -e  # exit immediately on any non-zero return code
set -u  # treat unset variables as errors
set -o pipefail  # fail if any command in a pipeline fails, not just the last

# Use the script's directory as the working directory, so you can run this from anywhere
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Bootstrap local .env from the committed template when missing (evaluator zero-config path).
if [[ ! -f .env ]]; then
  if [[ ! -f .env.example ]]; then
    echo "ERROR: .env.example is missing; cannot create local .env"
    exit 1
  fi
  cp .env.example .env
  echo "=== start-fresh: created .env from .env.example (local demo defaults) ==="
fi

# shellcheck disable=SC1091
source .env

echo "=== start-fresh: stopping all containers and removing volumes ==="
docker compose down -v --remove-orphans

echo "=== start-fresh: generating configs ==="
export MANAGEMENT_SERVICE_PORT DOWNLOAD_SERVICE_PORT
export MINIO_REGION MINIO_ARCHIVE_BUCKET MINIO_ROOT_USER MINIO_ROOT_PASSWORD
envsubst '${MANAGEMENT_SERVICE_PORT} ${DOWNLOAD_SERVICE_PORT}' < monitoring/prometheus.yml.template > monitoring/prometheus.yml
envsubst '${MINIO_REGION} ${MINIO_ARCHIVE_BUCKET} ${MINIO_ROOT_USER} ${MINIO_ROOT_PASSWORD}' < code/kafka-connect/config/audit-s3-sink.json.template > code/kafka-connect/config/audit-s3-sink.json
echo "  configs generated."

echo "=== start-fresh: building service images ==="
docker compose build management-service download-service kafka-connect test-client

echo "=== start-fresh: starting infrastructure ==="
docker compose up -d postgres keycloak minio kafka kafka-ui kafka-connect

echo "=== start-fresh: waiting for postgres ==="
until docker compose exec -T postgres pg_isready -U $DB_USER -d $DB_NAME 2>/dev/null; do
  echo "  waiting for postgres..."
  sleep 1
done
echo "  postgres ready."

echo "=== start-fresh: waiting for minio ==="
RETRIES=30
until curl -sf http://localhost:$MINIO_PORT/minio/health/live &>/dev/null; do
  RETRIES=$((RETRIES - 1))
  if [[ $RETRIES -eq 0 ]]; then
    echo "ERROR: minio did not become ready in time."
    docker compose logs minio | tail -20
    exit 1
  fi
  echo "  waiting for minio... ($RETRIES retries left)"
  sleep 2
done
echo "  minio ready."

echo "=== start-fresh: waiting for kafka ==="
RETRIES=30
until docker compose exec -T kafka kafka-topics --bootstrap-server kafka:9092 --list &>/dev/null; do
  RETRIES=$((RETRIES - 1))
  if [[ $RETRIES -eq 0 ]]; then
    echo "ERROR: kafka did not become ready in time."
    docker compose logs kafka | tail -20
    exit 1
  fi
  echo "  waiting for kafka... ($RETRIES retries left)"
  sleep 2
done
echo "  kafka ready."

echo "=== start-fresh: waiting for keycloak ==="
RETRIES=60
until curl -sf http://localhost:$KEYCLOAK_PORT/realms/secure-download-system &>/dev/null; do
  RETRIES=$((RETRIES - 1))
  if [[ $RETRIES -eq 0 ]]; then
    echo "ERROR: keycloak did not become ready in time."
    docker compose logs keycloak | tail -20
    exit 1
  fi
  echo "  waiting for keycloak... ($RETRIES retries left)"
  sleep 2
done
echo "  keycloak ready."

echo "=== start-fresh: waiting for kafka-connect ==="
RETRIES=60
until curl -sf http://localhost:${KAFKA_CONNECT_PORT}/connectors &>/dev/null; do
  RETRIES=$((RETRIES - 1))
  if [[ $RETRIES -eq 0 ]]; then
    echo "ERROR: kafka-connect did not become ready in time."
    docker compose logs kafka-connect | tail -20
    exit 1
  fi
  echo "  waiting for kafka-connect... ($RETRIES retries left)"
  sleep 5
done
echo "  kafka-connect ready."

echo "=== start-fresh: initializing infrastructure ==="
docker compose up -d minio-init kafka-init kafka-connect-init

echo "=== start-fresh: starting application services ==="
docker compose up -d management-service

echo "=== start-fresh: waiting for management-service ==="
RETRIES=60
until curl -sf http://localhost:${MANAGEMENT_SERVICE_PORT:-8081}/actuator/health | grep -q '"status":"UP"' &>/dev/null; do
  RETRIES=$((RETRIES - 1))
  if [[ $RETRIES -eq 0 ]]; then
    echo "ERROR: management-service did not become ready in time."
    docker compose logs management-service | tail -30
    exit 1
  fi
  echo "  waiting for management-service... ($RETRIES retries left)"
  sleep 3
done
echo "  management-service ready."

echo "=== start-fresh: starting download-service ==="
docker compose up -d download-service

echo "=== start-fresh: waiting for download-service ==="
RETRIES=60
until curl -sf http://localhost:${DOWNLOAD_SERVICE_PORT}/actuator/health | grep -q '"status":"UP"' &>/dev/null; do
  RETRIES=$((RETRIES - 1))
  if [[ $RETRIES -eq 0 ]]; then
    echo "ERROR: download-service did not become ready in time."
    docker compose logs download-service | tail -30
    exit 1
  fi
  echo "  waiting for download-service... ($RETRIES retries left)"
  sleep 3
done
echo "  download-service ready."

echo "=== start-fresh: starting test client and monitoring ==="
docker compose up -d test-client prometheus grafana

echo ""
echo "=== Stack ready ==="
echo "  Test UI:                http://localhost:${TEST_CLIENT_PORT}"
echo "  Management API docs:    http://localhost:${MANAGEMENT_SERVICE_PORT}/swagger-ui.html"
echo "  Download API docs:      http://localhost:${DOWNLOAD_SERVICE_PORT}/swagger-ui.html"
echo "  Keycloak:               http://localhost:${KEYCLOAK_PORT}"
echo "  Kafka UI:               http://localhost:${KAFKA_UI_PORT}"
echo "  MinIO console:          http://localhost:${MINIO_CONSOLE_PORT}"
echo "  Grafana:                http://localhost:${GRAFANA_PORT}  (admin / ${GRAFANA_ADMIN_PASSWORD})"
echo "  PostgreSQL:             localhost:${DB_PORT}"
