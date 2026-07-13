# Tear down all containers and volumes
# Usage: .\teardown.ps1

$ErrorActionPreference = "Stop"

Write-Host "==> Tearing down existing app containers and volumes..." -ForegroundColor Cyan
docker compose down -v --remove-orphans
Write-Host "Done." -ForegroundColor Green
