# Scrubs the environment clean, builds all services, and starts the full stack.
# Run this for a guaranteed clean slate before testing.
# Usage: .\start-fresh.ps1

$ErrorActionPreference = "Stop"

# Get script directory
$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $SCRIPT_DIR

# Bootstrap local .env from the committed template when missing (evaluator zero-config path).
if (-not (Test-Path .env)) {
  if (-not (Test-Path .env.example)) {
    Write-Host "ERROR: .env.example is missing; cannot create local .env" -ForegroundColor Red
    exit 1
  }
  Copy-Item .env.example .env
  Write-Host "=== start-fresh: created .env from .env.example (local demo defaults) ===" -ForegroundColor Cyan
}

# Load .env file
$envContent = Get-Content .env -Raw
$envLines = $envContent -split "`n"
$envVars = @{}
foreach ($line in $envLines) {
  $line = $line.Trim()
  if ($line -and -not $line.StartsWith("#")) {
    $parts = $line -split "=", 2
    if ($parts.Length -eq 2) {
      $envVars[$parts[0]] = $parts[1]
    }
  }
}

# Set environment variables in current session
$envVars.GetEnumerator() | ForEach-Object { Set-Item -Path "env:$($_.Key)" -Value $_.Value }

Write-Host "=== start-fresh: stopping all containers and removing volumes ===" -ForegroundColor Cyan
docker compose down -v --remove-orphans

Write-Host "=== start-fresh: generating configs ===" -ForegroundColor Cyan

# Replace template variables in prometheus.yml
$prometheusTemplate = Get-Content monitoring/prometheus.yml.template -Raw
$prometheusContent = $prometheusTemplate `
  -replace '\$\{MANAGEMENT_SERVICE_PORT\}', $env:MANAGEMENT_SERVICE_PORT `
  -replace '\$\{DOWNLOAD_SERVICE_PORT\}', $env:DOWNLOAD_SERVICE_PORT
Set-Content monitoring/prometheus.yml $prometheusContent -Encoding UTF8

# Replace template variables in audit-s3-sink.json
$sinkTemplate = Get-Content code/kafka-connect/config/audit-s3-sink.json.template -Raw
$sinkContent = $sinkTemplate `
  -replace '\$\{MINIO_REGION\}', $env:MINIO_REGION `
  -replace '\$\{MINIO_ARCHIVE_BUCKET\}', $env:MINIO_ARCHIVE_BUCKET `
  -replace '\$\{MINIO_ROOT_USER\}', $env:MINIO_ROOT_USER `
  -replace '\$\{MINIO_ROOT_PASSWORD\}', $env:MINIO_ROOT_PASSWORD
Set-Content code/kafka-connect/config/audit-s3-sink.json $sinkContent -Encoding UTF8

Write-Host "  configs generated."

Write-Host "=== start-fresh: building service images ===" -ForegroundColor Cyan
docker compose build management-service download-service kafka-connect test-client

Write-Host "=== start-fresh: starting infrastructure ===" -ForegroundColor Cyan
docker compose up -d postgres keycloak minio kafka kafka-ui kafka-connect

function Wait-Service {
  param (
    [string]$Name,
    [scriptblock]$Condition,
    [int]$MaxRetries = 30,
    [int]$RetryDelaySeconds = 2,
    [int]$FinalRetryDelaySeconds = 5
  )

  Write-Host "=== start-fresh: waiting for $Name ===" -ForegroundColor Cyan
  $RETRIES = $MaxRetries
  while ($RETRIES -gt 0) {
    if (& $Condition) {
      Write-Host "  $Name ready."
      return $true
    }
    $RETRIES--
    if ($RETRIES -eq 0) {
      Write-Host "ERROR: $Name did not become ready in time." -ForegroundColor Red
      docker compose logs $Name | Select-Object -Last 20 | Out-Host
      exit 1
    }
    Write-Host "  waiting for $Name... ($RETRIES retries left)"
    Start-Sleep -Seconds $RetryDelaySeconds
  }
}

# Wait for PostgreSQL
Wait-Service "postgres" {
  $output = docker compose exec -T postgres pg_isready -U $env:DB_USER -d $env:DB_NAME 2>$null
  return $LASTEXITCODE -eq 0
} 30 1

# Wait for MinIO
Wait-Service "minio" {
  try {
    $response = Invoke-WebRequest -Uri "http://localhost:$($env:MINIO_PORT)/minio/health/live" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
    return $response.StatusCode -eq 200
  } catch {
    return $false
  }
} 30 2

# Wait for Kafka
Wait-Service "kafka" {
  $output = docker compose exec -T kafka kafka-topics --bootstrap-server kafka:9092 --list 2>$null
  return $LASTEXITCODE -eq 0
} 30 2

# Wait for Keycloak
Wait-Service "keycloak" {
  try {
    $response = Invoke-WebRequest -Uri "http://localhost:$($env:KEYCLOAK_PORT)/realms/secure-download-system" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
    return $response.StatusCode -eq 200
  } catch {
    return $false
  }
} 60 2

# Wait for Kafka Connect
Wait-Service "kafka-connect" {
  try {
    $response = Invoke-WebRequest -Uri "http://localhost:$($env:KAFKA_CONNECT_PORT)/connectors" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
    return $response.StatusCode -eq 200
  } catch {
    return $false
  }
} 60 5

Write-Host "=== start-fresh: initializing infrastructure ===" -ForegroundColor Cyan
docker compose up -d minio-init kafka-init kafka-connect-init

Write-Host "=== start-fresh: starting application services ===" -ForegroundColor Cyan
docker compose up -d management-service

# Wait for Management Service
Wait-Service "management-service" {
  try {
    $response = Invoke-WebRequest -Uri "http://localhost:$($env:MANAGEMENT_SERVICE_PORT -replace '8081', '8081')/actuator/health" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
    $json = $response.Content | ConvertFrom-Json
    return $json.status -eq "UP"
  } catch {
    return $false
  }
} 60 3

Write-Host "=== start-fresh: starting download-service ===" -ForegroundColor Cyan
docker compose up -d download-service

# Wait for Download Service
Wait-Service "download-service" {
  try {
    $response = Invoke-WebRequest -Uri "http://localhost:$($env:DOWNLOAD_SERVICE_PORT)/actuator/health" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
    $json = $response.Content | ConvertFrom-Json
    return $json.status -eq "UP"
  } catch {
    return $false
  }
} 60 3

Write-Host "=== start-fresh: starting test client and monitoring ===" -ForegroundColor Cyan
docker compose up -d test-client prometheus grafana

Write-Host ""
Write-Host "=== Stack ready ===" -ForegroundColor Green
Write-Host "  Test UI:                http://localhost:$($env:TEST_CLIENT_PORT)"
Write-Host "  Management API docs:    http://localhost:$($env:MANAGEMENT_SERVICE_PORT)/swagger-ui.html"
Write-Host "  Download API docs:      http://localhost:$($env:DOWNLOAD_SERVICE_PORT)/swagger-ui.html"
Write-Host "  Keycloak:               http://localhost:$($env:KEYCLOAK_PORT)"
Write-Host "  Kafka UI:               http://localhost:$($env:KAFKA_UI_PORT)"
Write-Host "  MinIO console:          http://localhost:$($env:MINIO_CONSOLE_PORT)"
Write-Host "  Grafana:                http://localhost:$($env:GRAFANA_PORT)  (admin / $($env:GRAFANA_ADMIN_PASSWORD))"
Write-Host "  PostgreSQL:             localhost:$($env:DB_PORT)"
