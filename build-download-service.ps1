# Build download service using Docker
# Usage: .\build-download-service.ps1

$ErrorActionPreference = "Stop"

$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "Building download-service..." -ForegroundColor Cyan

docker run --rm `
  -v "$SCRIPT_DIR/code:/code" `
  -w /code/download-service `
  maven:3.9-eclipse-temurin-25 `
  /bin/bash -c "
    set -e
    keytool -importcert -noprompt -trustcacerts -alias zscaler-ca `
      -file /code/certs/zscaler-ca.pem -cacerts -storepass changeit 2>/dev/null || true
    mvn package -DskipTests
    chown -R 1000:1000 /code/download-service/target 2>/dev/null || true
  "

if ($LASTEXITCODE -eq 0) {
  Write-Host "Build successful." -ForegroundColor Green
} else {
  Write-Host "Build failed." -ForegroundColor Red
  exit 1
}
