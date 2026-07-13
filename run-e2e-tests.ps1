# End-to-end checks against a running stack (.\start-fresh.ps1 first).
# Covers: upload, auth, non-PDF rejection, download/exhaustion, revoke, 404 token, rate limit.
# Usage: .\run-e2e-tests.ps1

$ErrorActionPreference = "Continue"

$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $SCRIPT_DIR

if (-not (Test-Path .env)) {
  if (Test-Path .env.example) {
    Copy-Item .env.example .env
    Write-Host "Created .env from .env.example (local demo defaults)." -ForegroundColor Cyan
  } else {
    Write-Host "ERROR: .env missing and .env.example not found. Run .\start-fresh.ps1 first." -ForegroundColor Red
    exit 1
  }
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

$PASS = 0
$FAIL = 0

function Pass-Test {
  param([string]$label)
  Write-Host "  PASS: $label" -ForegroundColor Green
  $global:PASS++
}

function Fail-Test {
  param([string]$label)
  Write-Host "  FAIL: $label" -ForegroundColor Red
  $global:FAIL++
}

function Assert-Equal {
  param([string]$label, [string]$expected, [string]$actual)
  if ("$actual" -eq "$expected") {
    Pass-Test $label
  } else {
    Fail-Test "$label (expected='$expected' actual='$actual')"
  }
}

function Assert-NotEmpty {
  param([string]$label, [string]$value)
  if ($value) {
    Pass-Test $label
  } else {
    Fail-Test "$label (was empty)"
  }
}

function Invoke-DbQuery {
  param([string]$query)
  $output = docker compose exec -T postgres psql -U $env:DB_USER -d $env:DB_NAME -t -A -c $query 2>$null
  return "$output".Trim()
}

function Get-HttpStatus {
  param([scriptblock]$Request)
  try {
    $resp = & $Request
    return [int]$resp.StatusCode
  } catch {
    if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
      return [int]$_.Exception.Response.StatusCode.value__
    }
    return 0
  }
}

# ── Config ────────────────────────────────────────────────────────────────────

$KEYCLOAK_BASE = "http://localhost:$($env:KEYCLOAK_PORT)"
$MGMT_BASE = "http://localhost:$($env:MANAGEMENT_SERVICE_PORT)"
$DOWNLOAD_BASE = "http://localhost:$($env:DOWNLOAD_SERVICE_PORT)"
$REALM = "secure-download-system"
$CLIENT_ID = "sds-sample-client"
$CLIENT_SECRET = $env:SDS_SAMPLE_CLIENT_SECRET

# ── Test: Upload ───────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "=== E2E: Upload document ===" -ForegroundColor Cyan

# 1. Get token
try {
  $tokenResponse = Invoke-WebRequest -Uri "$KEYCLOAK_BASE/realms/$REALM/protocol/openid-connect/token" `
    -Method Post `
    -Body @{
      grant_type = "client_credentials"
      client_id = $CLIENT_ID
      client_secret = $CLIENT_SECRET
    } `
    -UseBasicParsing -ErrorAction Stop
  $tokenJson = $tokenResponse.Content | ConvertFrom-Json
  $TOKEN = $tokenJson.access_token
} catch {
  Fail-Test "token retrieved"
  $TOKEN = ""
}

Assert-NotEmpty "token retrieved" $TOKEN

# 2. Create a minimal valid PDF
$TMPDIR = New-Item -ItemType Directory -Path "$([System.IO.Path]::GetTempPath())sds-test-$([System.Guid]::NewGuid())" | Select-Object -ExpandProperty FullName
$PDF = Join-Path $TMPDIR "test.pdf"
Set-Content -Path $PDF -Value "%PDF-1.4`n%%EOF" -Encoding ASCII

# 3. Upload document
try {
  $uploadResponse = Invoke-WebRequest -Uri "$MGMT_BASE/api/v1/documents" `
    -Method Post `
    -Headers @{
      "Authorization" = "Bearer $TOKEN"
      "Content-Type" = "application/pdf"
      "x-capitec-max-downloads" = "3"
      "x-capitec-expires-in" = "3600"
    } `
    -InFile $PDF `
    -UseBasicParsing -ErrorAction Stop
  $uploadJson = $uploadResponse.Content | ConvertFrom-Json
  $DOCUMENT_ID = $uploadJson.documentId
  $DOWNLOAD_URL = $uploadJson.downloadUrl
} catch {
  Fail-Test "document upload"
  $DOCUMENT_ID = ""
  $DOWNLOAD_URL = ""
}

Assert-NotEmpty "upload returned documentId" $DOCUMENT_ID
Assert-NotEmpty "upload returned downloadUrl" $DOWNLOAD_URL

# 4. Verify document in DB
$DB_STATUS = Invoke-DbQuery "SELECT status FROM document WHERE id = '$DOCUMENT_ID';"
Assert-equal "document persisted with ACTIVE status" "ACTIVE" $DB_STATUS

$DB_MAX = Invoke-DbQuery "SELECT max_downloads FROM document WHERE id = '$DOCUMENT_ID';"
Assert-Equal "max_downloads persisted correctly" "3" $DB_MAX

$DB_COUNT = Invoke-DbQuery "SELECT download_count FROM document WHERE id = '$DOCUMENT_ID';"
Assert-Equal "download_count starts at 0" "0" $DB_COUNT

$DB_CREATED_BY = Invoke-DbQuery "SELECT created_by FROM document WHERE id = '$DOCUMENT_ID';"
Assert-NotEmpty "created_by is set" $DB_CREATED_BY

# 5. Verify audit event in DB
$AUDIT_COUNT = Invoke-DbQuery "SELECT COUNT(*) FROM audit_events WHERE document_id = '$DOCUMENT_ID' AND event_type = 'UPLOAD' AND result = 'SUCCESS';"
Assert-equal "UPLOAD audit event recorded" "1" $AUDIT_COUNT

# 6. Verify outbox entry created
$OUTBOX_COUNT = Invoke-DbQuery "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = '$DOCUMENT_ID' AND status = 'PENDING';"
if ([int]$OUTBOX_COUNT -ge 1) {
  Pass-Test "outbox entries created"
} else {
  Fail-Test "outbox entries created (expected >= 1, actual=$OUTBOX_COUNT)"
}

# 7. Verify via GET endpoint
try {
  $getResponse = Invoke-WebRequest -Uri "$MGMT_BASE/api/v1/documents/$DOCUMENT_ID" `
    -Method Get `
    -Headers @{ "Authorization" = "Bearer $TOKEN" } `
    -UseBasicParsing -ErrorAction Stop
  $getJson = $getResponse.Content | ConvertFrom-Json
  $GET_STATUS = $getJson.status
} catch {
  $GET_STATUS = ""
}

Assert-Equal "GET returns ACTIVE status" "ACTIVE" $GET_STATUS

# ── Test: Auth failures ───────────────────────────────────────────────────────

Write-Host ""
Write-Host "=== E2E: Auth failures ===" -ForegroundColor Cyan

$UNAUTH_STATUS = Get-HttpStatus {
  Invoke-WebRequest -Uri "$MGMT_BASE/api/v1/documents" `
    -Method Post `
    -Headers @{
      "Content-Type" = "application/pdf"
      "x-capitec-max-downloads" = "1"
      "x-capitec-expires-in" = "60"
    } `
    -InFile $PDF `
    -UseBasicParsing -ErrorAction Stop
}
Assert-equal "upload without token returns 401" "401" "$UNAUTH_STATUS"

$BAD_AUTH_STATUS = Get-HttpStatus {
  Invoke-WebRequest -Uri "$MGMT_BASE/api/v1/documents" `
    -Method Post `
    -Headers @{
      "Authorization" = "Bearer not-a-valid-jwt"
      "Content-Type" = "application/pdf"
      "x-capitec-max-downloads" = "1"
      "x-capitec-expires-in" = "60"
    } `
    -InFile $PDF `
    -UseBasicParsing -ErrorAction Stop
}
Assert-equal "upload with invalid token returns 401" "401" "$BAD_AUTH_STATUS"

$GET_UNAUTH = Get-HttpStatus {
  Invoke-WebRequest -Uri "$MGMT_BASE/api/v1/documents/$DOCUMENT_ID" `
    -Method Get `
    -UseBasicParsing -ErrorAction Stop
}
Assert-equal "GET document without token returns 401" "401" "$GET_UNAUTH"

# ── Test: Non-PDF rejection ───────────────────────────────────────────────────

Write-Host ""
Write-Host "=== E2E: Non-PDF rejection ===" -ForegroundColor Cyan

$NON_PDF_FILE = Join-Path $SCRIPT_DIR "sample-documents\non_pdf.pdf"
if (-not (Test-Path $NON_PDF_FILE)) {
  $NON_PDF_FILE = Join-Path $TMPDIR "not.pdf"
  Set-Content -Path $NON_PDF_FILE -Value "not-a-pdf-at-all" -Encoding ASCII
}

$NON_PDF_STATUS = Get-HttpStatus {
  Invoke-WebRequest -Uri "$MGMT_BASE/api/v1/documents" `
    -Method Post `
    -Headers @{
      "Authorization" = "Bearer $TOKEN"
      "Content-Type" = "application/pdf"
      "x-capitec-max-downloads" = "1"
      "x-capitec-expires-in" = "60"
    } `
    -InFile $NON_PDF_FILE `
    -UseBasicParsing -ErrorAction Stop
}
Assert-equal "non-PDF body returns 415 Unsupported Media Type" "415" "$NON_PDF_STATUS"

# ── Test: Download ────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "=== E2E: Download document ===" -ForegroundColor Cyan

# Upload a fresh document with max_downloads=2 for exhaustion test
try {
  $upload2Response = Invoke-WebRequest -Uri "$MGMT_BASE/api/v1/documents" `
    -Method Post `
    -Headers @{
      "Authorization" = "Bearer $TOKEN"
      "Content-Type" = "application/pdf"
      "x-capitec-max-downloads" = "2"
      "x-capitec-expires-in" = "20"
    } `
    -InFile $PDF `
    -UseBasicParsing -ErrorAction Stop
  $upload2Json = $upload2Response.Content | ConvertFrom-Json
  $DOC2_ID = $upload2Json.documentId
  $DOC2_URL = $upload2Json.downloadUrl
  $DOC2_TOKEN = $DOC2_URL -replace ".*/"
} catch {
  $DOC2_ID = ""
  $DOC2_URL = ""
  $DOC2_TOKEN = ""
}

Assert-NotEmpty "exhaustion doc uploaded" $DOC2_ID

# 1. First download — should succeed and return PDF bytes
$DL1_PATH = Join-Path $TMPDIR "dl1.pdf"
try {
  $dl1Response = Invoke-WebRequest -Uri "$DOWNLOAD_BASE/download/$DOC2_TOKEN" `
    -Method Get `
    -OutFile $DL1_PATH `
    -UseBasicParsing -ErrorAction Stop
  $DL_STATUS = 200
} catch {
  $DL_STATUS = 999
}

Assert-equal "first download returns 200" "200" "$DL_STATUS"

$bytes = [System.IO.File]::ReadAllBytes($DL1_PATH)[0..3]
$DL_MAGIC = -join ($bytes | ForEach-Object { [char]$_ })
Assert-Equal "downloaded file has PDF magic bytes" "%PDF" $DL_MAGIC

# Give Kafka time to process DOWNLOAD_COMPLETE audit event
Start-Sleep -Seconds 2

$DB_COUNT1 = Invoke-DbQuery "SELECT download_count FROM document WHERE id = '$DOC2_ID';"
Assert-Equal "download_count incremented to 1 after first download" "1" $DB_COUNT1

$AUDIT_DL1 = Invoke-DbQuery "SELECT COUNT(*) FROM audit_events WHERE document_id = '$DOC2_ID' AND event_type = 'DOWNLOAD_COMPLETE' AND result = 'SUCCESS';"
Assert-equal "DOWNLOAD_COMPLETE audit event recorded" "1" $AUDIT_DL1

# 2. Second download — should succeed (count reaches max)
$DL2_PATH = Join-Path $TMPDIR "dl2.pdf"
try {
  Invoke-WebRequest -Uri "$DOWNLOAD_BASE/download/$DOC2_TOKEN" `
    -Method Get `
    -OutFile $DL2_PATH `
    -UseBasicParsing -ErrorAction Stop | Out-Null
  $DL2_STATUS = 200
} catch {
  $DL2_STATUS = 999
}

Assert-equal "second download returns 200" "200" "$DL2_STATUS"

Start-Sleep -Seconds 2

$DB_COUNT2 = Invoke-DbQuery "SELECT download_count FROM document WHERE id = '$DOC2_ID';"
Assert-equal "download_count incremented to 2 after second download" "2" $DB_COUNT2

$DB_STATUS2 = Invoke-DbQuery "SELECT status FROM document WHERE id = '$DOC2_ID';"
if ($DB_STATUS2 -eq "EXHAUSTED" -or $DB_STATUS2 -eq "ARCHIVED") {
  Pass-Test "document status EXHAUSTED (or ARCHIVED) after reaching max_downloads"
} else {
  Fail-Test "document status EXHAUSTED (or ARCHIVED) after reaching max_downloads (expected='EXHAUSTED|ARCHIVED' actual='$DB_STATUS2')"
}

# 3. Third download — should be rejected (exhausted)
$DL3_STATUS = Get-HttpStatus {
  Invoke-WebRequest -Uri "$DOWNLOAD_BASE/download/$DOC2_TOKEN" `
    -Method Get `
    -UseBasicParsing -ErrorAction Stop
}
Assert-equal "third download rejected with 410 when exhausted" "410" "$DL3_STATUS"

# ── Test: Unknown download token ──────────────────────────────────────────────

Write-Host ""
Write-Host "=== E2E: Unknown download token ===" -ForegroundColor Cyan

$UNK_STATUS = Get-HttpStatus {
  Invoke-WebRequest -Uri "$DOWNLOAD_BASE/download/00000000" `
    -Method Get `
    -UseBasicParsing -ErrorAction Stop
}
Assert-equal "unknown download token returns 404" "404" "$UNK_STATUS"

# ── Test: Revoke ──────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "=== E2E: Revoke document ===" -ForegroundColor Cyan

try {
  $uploadRevResponse = Invoke-WebRequest -Uri "$MGMT_BASE/api/v1/documents" `
    -Method Post `
    -Headers @{
      "Authorization" = "Bearer $TOKEN"
      "Content-Type" = "application/pdf"
      "x-capitec-max-downloads" = "5"
      "x-capitec-expires-in" = "3600"
    } `
    -InFile $PDF `
    -UseBasicParsing -ErrorAction Stop
  $uploadRevJson = $uploadRevResponse.Content | ConvertFrom-Json
  $REV_ID = $uploadRevJson.documentId
  $REV_TOKEN = $uploadRevJson.downloadUrl -replace ".*/"
} catch {
  $REV_ID = ""
  $REV_TOKEN = ""
}

Assert-NotEmpty "revoke test doc uploaded" $REV_ID

$PRE_REV_DL = Get-HttpStatus {
  Invoke-WebRequest -Uri "$DOWNLOAD_BASE/download/$REV_TOKEN" `
    -Method Get `
    -UseBasicParsing -ErrorAction Stop
}
Assert-equal "download works before revoke" "200" "$PRE_REV_DL"

$REV_HTTP = Get-HttpStatus {
  Invoke-WebRequest -Uri "$MGMT_BASE/api/v1/documents/$REV_ID" `
    -Method Delete `
    -Headers @{ "Authorization" = "Bearer $TOKEN" } `
    -UseBasicParsing -ErrorAction Stop
}
Assert-equal "revoke returns 204" "204" "$REV_HTTP"

$DB_REV_STATUS = Invoke-DbQuery "SELECT status FROM document WHERE id = '$REV_ID';"
Assert-Equal "document status REVOKED after revoke" "REVOKED" $DB_REV_STATUS

$AUDIT_REV = Invoke-DbQuery "SELECT COUNT(*) FROM audit_events WHERE document_id = '$REV_ID' AND event_type = 'REVOKE' AND result = 'SUCCESS';"
Assert-equal "REVOKE audit event recorded" "1" $AUDIT_REV

$POST_REV_DL = Get-HttpStatus {
  Invoke-WebRequest -Uri "$DOWNLOAD_BASE/download/$REV_TOKEN" `
    -Method Get `
    -UseBasicParsing -ErrorAction Stop
}
Assert-equal "download after revoke returns 410" "410" "$POST_REV_DL"

# ── Test: Rate limiting ───────────────────────────────────────────────────────
# Last: burns rate-limit budget for this IP on the download service.

Write-Host ""
Write-Host "=== E2E: Rate limiting ===" -ForegroundColor Cyan

$RATE_LIMIT = if ($env:RATE_LIMIT_REQUESTS_PER_MINUTE) { [int]$env:RATE_LIMIT_REQUESTS_PER_MINUTE } else { 10 }

try {
  $uploadRLResponse = Invoke-WebRequest -Uri "$MGMT_BASE/api/v1/documents" `
    -Method Post `
    -Headers @{
      "Authorization" = "Bearer $TOKEN"
      "Content-Type" = "application/pdf"
      "x-capitec-max-downloads" = ($RATE_LIMIT + 10)
      "x-capitec-expires-in" = "60"
    } `
    -InFile $PDF `
    -UseBasicParsing -ErrorAction Stop
  $uploadRLJson = $uploadRLResponse.Content | ConvertFrom-Json
  $RL_TOKEN = $uploadRLJson.downloadUrl -replace ".*/"
} catch {
  $RL_TOKEN = ""
}

Assert-NotEmpty "rate limit test doc uploaded" $RL_TOKEN

$GOT_429 = $false
$MAX_ATTEMPTS = $RATE_LIMIT * 2 + 5

for ($i = 1; $i -le $MAX_ATTEMPTS; $i++) {
  $RL_STATUS = Get-HttpStatus {
    Invoke-WebRequest -Uri "$DOWNLOAD_BASE/download/$RL_TOKEN" `
      -Method Get `
      -TimeoutSec 5 `
      -UseBasicParsing -ErrorAction Stop
  }

  if ($RL_STATUS -eq 429) {
    $GOT_429 = $true
    Pass-Test "rate limit enforced after $i requests (429 received)"
    break
  }
}

if (-not $GOT_429) {
  Fail-Test "rate limit never triggered within $MAX_ATTEMPTS requests"
}

# ── Summary ───────────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "=== Results: $PASS passed, $FAIL failed ===" -ForegroundColor Cyan

Remove-Item -Path $TMPDIR -Recurse -Force -ErrorAction SilentlyContinue

if ($FAIL -eq 0) {
  Write-Host "All tests passed!" -ForegroundColor Green
  exit 0
} else {
  Write-Host "Some tests failed!" -ForegroundColor Red
  exit 1
}
