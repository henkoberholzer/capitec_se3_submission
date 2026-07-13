# Run unit tests (and checkstyle via Maven validate) for both services.
# Usage: .\run-unit-tests.ps1
#
# Matches the CI "unit-tests" job: both management-service and download-service.
# Docker runs as root for keytool; ownership of target/ is restored afterward.

$ErrorActionPreference = "Stop"

$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
# Windows Docker Desktop often maps files as the user already; chown is a no-op/harmless on Linux containers.
$HOST_UID = "1000"
$HOST_GID = "1000"
if (Get-Command id -ErrorAction SilentlyContinue) {
  $HOST_UID = (id -u)
  $HOST_GID = (id -g)
}

function Invoke-ServiceTests {
  param([string]$Service)

  Write-Host "=== Unit tests: $Service ===" -ForegroundColor Cyan

  docker run --rm `
    -v "${SCRIPT_DIR}/code:/code" `
    -w "/code/${Service}" `
    maven:3.9-eclipse-temurin-25 `
    /bin/bash -c "
      set -e
      keytool -importcert -noprompt -trustcacerts -alias zscaler-ca `
        -file /code/certs/zscaler-ca.pem -cacerts -storepass changeit 2>/dev/null || true
      mvn test
      chown -R ${HOST_UID}:${HOST_GID} /code/${Service}/target 2>/dev/null || true
    "

  if ($LASTEXITCODE -ne 0) {
    Write-Host "Unit tests failed for $Service." -ForegroundColor Red
    exit $LASTEXITCODE
  }
}

Invoke-ServiceTests -Service "management-service"
Invoke-ServiceTests -Service "download-service"

Write-Host "All unit tests passed." -ForegroundColor Green
