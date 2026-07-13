#!/usr/bin/env bash

# End-to-end checks against a running stack (./start-fresh.sh first).
# Covers: upload, auth, non-PDF rejection, download/exhaustion, revoke, 404 token, rate limit.

#set -e  # exit immediately on any non-zero return code
#set -u  # treat unset variables as errors
#set -o pipefail  # fail if any command in a pipeline fails, not just the last

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ ! -f .env ]]; then
  if [[ -f .env.example ]]; then
    cp .env.example .env
    echo "Created .env from .env.example (local demo defaults)."
  else
    echo "ERROR: .env missing and .env.example not found. Run ./start-fresh.sh first."
    exit 1
  fi
fi

# shellcheck disable=SC1091
source .env

PASS=0
FAIL=0

pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }

assert_eq() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    pass "$label"
  else
    fail "$label (expected='$expected' actual='$actual')"
  fi
}

assert_not_empty() {
  local label="$1" value="$2"
  if [[ -n "$value" ]]; then
    pass "$label"
  else
    fail "$label (was empty)"
  fi
}

# ── Config ────────────────────────────────────────────────────────────────────

KEYCLOAK_BASE="http://localhost:${KEYCLOAK_PORT}"
MGMT_BASE="http://localhost:${MANAGEMENT_SERVICE_PORT}"
DOWNLOAD_BASE="http://localhost:${DOWNLOAD_SERVICE_PORT}"
REALM="secure-download-system"
CLIENT_ID="sds-sample-client"
CLIENT_SECRET="${SDS_SAMPLE_CLIENT_SECRET}"
DB_CONTAINER="postgres"

# ── Helpers ───────────────────────────────────────────────────────────────────

db_query() {
  docker compose exec -T "$DB_CONTAINER" \
    psql -U "$DB_USER" -d "$DB_NAME" -t -A -c "$1" 2>/dev/null
}

# ── Test: Upload ───────────────────────────────────────────────────────────────

echo ""
echo "=== E2E: Upload document ==="

# 1. Get token
TOKEN=$(curl -sf \
  -d "grant_type=client_credentials" \
  -d "client_id=${CLIENT_ID}" \
  -d "client_secret=${CLIENT_SECRET}" \
  "${KEYCLOAK_BASE}/realms/${REALM}/protocol/openid-connect/token" \
  | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)

assert_not_empty "token retrieved" "$TOKEN"

# 2. Create a minimal valid PDF (magic bytes + minimal structure)
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT
PDF="$TMPDIR/test.pdf"
printf '%s\n' '%PDF-1.4' '%%EOF' > "$PDF"

# 3. Upload document
UPLOAD_RESPONSE=$(curl -sf \
  -X POST "${MGMT_BASE}/api/v1/documents" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/pdf" \
  -H "x-capitec-max-downloads: 3" \
  -H "x-capitec-expires-in: 3600" \
  --data-binary @"$PDF")

DOCUMENT_ID=$(echo "$UPLOAD_RESPONSE" | grep -o '"documentId":"[^"]*"' | cut -d'"' -f4)
DOWNLOAD_URL=$(echo "$UPLOAD_RESPONSE" | grep -o '"downloadUrl":"[^"]*"' | cut -d'"' -f4)

assert_not_empty "upload returned documentId" "$DOCUMENT_ID"
assert_not_empty "upload returned downloadUrl" "$DOWNLOAD_URL"

# 4. Verify document in DB
DB_STATUS=$(db_query "SELECT status FROM document WHERE id = '${DOCUMENT_ID}';")
assert_eq "document persisted with ACTIVE status" "ACTIVE" "$DB_STATUS"

DB_MAX=$(db_query "SELECT max_downloads FROM document WHERE id = '${DOCUMENT_ID}';")
assert_eq "max_downloads persisted correctly" "3" "$DB_MAX"

DB_COUNT=$(db_query "SELECT download_count FROM document WHERE id = '${DOCUMENT_ID}';")
assert_eq "download_count starts at 0" "0" "$DB_COUNT"

DB_CREATED_BY=$(db_query "SELECT created_by FROM document WHERE id = '${DOCUMENT_ID}';")
assert_not_empty "created_by is set" "$DB_CREATED_BY"

# 5. Verify audit event in DB
AUDIT_COUNT=$(db_query "SELECT COUNT(*) FROM audit_events WHERE document_id = '${DOCUMENT_ID}' AND event_type = 'UPLOAD' AND result = 'SUCCESS';")
assert_eq "UPLOAD audit event recorded" "1" "$AUDIT_COUNT"

# 6. Verify outbox entry created
OUTBOX_COUNT=$(db_query "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = '${DOCUMENT_ID}' AND status = 'PENDING';")
if [[ "$OUTBOX_COUNT" -ge 1 ]]; then
  pass "outbox entries created"
else
  fail "outbox entries created (expected >= 1, actual=${OUTBOX_COUNT})"
fi

# 7. Verify via GET endpoint
GET_RESPONSE=$(curl -sf \
  "${MGMT_BASE}/api/v1/documents/${DOCUMENT_ID}" \
  -H "Authorization: Bearer ${TOKEN}")

GET_STATUS=$(echo "$GET_RESPONSE" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
assert_eq "GET returns ACTIVE status" "ACTIVE" "$GET_STATUS"

# ── Test: Auth failures ───────────────────────────────────────────────────────

echo ""
echo "=== E2E: Auth failures ==="

UNAUTH_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "${MGMT_BASE}/api/v1/documents" \
  -H "Content-Type: application/pdf" \
  -H "x-capitec-max-downloads: 1" \
  -H "x-capitec-expires-in: 60" \
  --data-binary @"$PDF")
assert_eq "upload without token returns 401" "401" "$UNAUTH_STATUS"

BAD_AUTH_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "${MGMT_BASE}/api/v1/documents" \
  -H "Authorization: Bearer not-a-valid-jwt" \
  -H "Content-Type: application/pdf" \
  -H "x-capitec-max-downloads: 1" \
  -H "x-capitec-expires-in: 60" \
  --data-binary @"$PDF")
assert_eq "upload with invalid token returns 401" "401" "$BAD_AUTH_STATUS"

GET_UNAUTH=$(curl -s -o /dev/null -w "%{http_code}" \
  "${MGMT_BASE}/api/v1/documents/${DOCUMENT_ID}")
assert_eq "GET document without token returns 401" "401" "$GET_UNAUTH"

# ── Test: Non-PDF rejection ───────────────────────────────────────────────────

echo ""
echo "=== E2E: Non-PDF rejection ==="

NON_PDF_FILE="${SCRIPT_DIR}/sample-documents/non_pdf.pdf"
if [[ ! -f "$NON_PDF_FILE" ]]; then
  NON_PDF_FILE="$TMPDIR/not.pdf"
  printf '%s\n' 'not-a-pdf-at-all' > "$NON_PDF_FILE"
fi

NON_PDF_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "${MGMT_BASE}/api/v1/documents" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/pdf" \
  -H "x-capitec-max-downloads: 1" \
  -H "x-capitec-expires-in: 60" \
  --data-binary @"$NON_PDF_FILE")
assert_eq "non-PDF body returns 415 Unsupported Media Type" "415" "$NON_PDF_STATUS"

# ── Test: Download ────────────────────────────────────────────────────────────

echo ""
echo "=== E2E: Download document ==="

# Upload a fresh document with max_downloads=2 for exhaustion test
UPLOAD2=$(curl -sf \
  -X POST "${MGMT_BASE}/api/v1/documents" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/pdf" \
  -H "x-capitec-max-downloads: 2" \
  -H "x-capitec-expires-in: 20" \
  --data-binary @"$PDF")

DOC2_ID=$(echo "$UPLOAD2" | grep -o '"documentId":"[^"]*"' | cut -d'"' -f4)
DOC2_URL=$(echo "$UPLOAD2" | grep -o '"downloadUrl":"[^"]*"' | cut -d'"' -f4)
DOC2_TOKEN=$(echo "$DOC2_URL" | grep -o '[^/]*$')

assert_not_empty "exhaustion doc uploaded" "$DOC2_ID"

# 1. First download — should succeed and return PDF bytes
DL_STATUS=$(curl -sf -o "$TMPDIR/dl1.pdf" -w "%{http_code}" "${DOWNLOAD_BASE}/download/${DOC2_TOKEN}")
assert_eq "first download returns 200" "200" "$DL_STATUS"

DL_MAGIC=$(head -c 4 "$TMPDIR/dl1.pdf" 2>/dev/null || true)
assert_eq "downloaded file has PDF magic bytes" "%PDF" "$DL_MAGIC"

# Give Kafka time to process DOWNLOAD_COMPLETE audit event and increment count
sleep 2

DB_COUNT1=$(db_query "SELECT download_count FROM document WHERE id = '${DOC2_ID}';")
assert_eq "download_count incremented to 1 after first download" "1" "$DB_COUNT1"

AUDIT_DL1=$(db_query "SELECT COUNT(*) FROM audit_events WHERE document_id = '${DOC2_ID}' AND event_type = 'DOWNLOAD_COMPLETE' AND result = 'SUCCESS';")
assert_eq "DOWNLOAD_COMPLETE audit event recorded" "1" "$AUDIT_DL1"

# 2. Second download — should succeed (count reaches max)
DL2_STATUS=$(curl -sf -o "$TMPDIR/dl2.pdf" -w "%{http_code}" "${DOWNLOAD_BASE}/download/${DOC2_TOKEN}")
assert_eq "second download returns 200" "200" "$DL2_STATUS"

sleep 2

DB_COUNT2=$(db_query "SELECT download_count FROM document WHERE id = '${DOC2_ID}';")
assert_eq "download_count incremented to 2 after second download" "2" "$DB_COUNT2"

DB_STATUS2=$(db_query "SELECT status FROM document WHERE id = '${DOC2_ID}';")
if [[ "$DB_STATUS2" == "EXHAUSTED" || "$DB_STATUS2" == "ARCHIVED" ]]; then
  pass "document status EXHAUSTED (or ARCHIVED) after reaching max_downloads"
else
  fail "document status EXHAUSTED (or ARCHIVED) after reaching max_downloads (expected='EXHAUSTED|ARCHIVED' actual='${DB_STATUS2}')"
fi

# 3. Third download — should be rejected (exhausted)
DL3_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${DOWNLOAD_BASE}/download/${DOC2_TOKEN}")
assert_eq "third download rejected with 410 when exhausted" "410" "$DL3_STATUS"

# ── Test: Unknown download token ──────────────────────────────────────────────

echo ""
echo "=== E2E: Unknown download token ==="

UNK_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${DOWNLOAD_BASE}/download/00000000")
assert_eq "unknown download token returns 404" "404" "$UNK_STATUS"

# ── Test: Revoke ──────────────────────────────────────────────────────────────

echo ""
echo "=== E2E: Revoke document ==="

UPLOAD_REV=$(curl -sf \
  -X POST "${MGMT_BASE}/api/v1/documents" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/pdf" \
  -H "x-capitec-max-downloads: 5" \
  -H "x-capitec-expires-in: 3600" \
  --data-binary @"$PDF")

REV_ID=$(echo "$UPLOAD_REV" | grep -o '"documentId":"[^"]*"' | cut -d'"' -f4)
REV_URL=$(echo "$UPLOAD_REV" | grep -o '"downloadUrl":"[^"]*"' | cut -d'"' -f4)
REV_TOKEN=$(echo "$REV_URL" | grep -o '[^/]*$')
assert_not_empty "revoke test doc uploaded" "$REV_ID"

# Confirm download works before revoke
PRE_REV_DL=$(curl -s -o /dev/null -w "%{http_code}" "${DOWNLOAD_BASE}/download/${REV_TOKEN}")
assert_eq "download works before revoke" "200" "$PRE_REV_DL"

REV_HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
  -X DELETE "${MGMT_BASE}/api/v1/documents/${REV_ID}" \
  -H "Authorization: Bearer ${TOKEN}")
assert_eq "revoke returns 204" "204" "$REV_HTTP"

DB_REV_STATUS=$(db_query "SELECT status FROM document WHERE id = '${REV_ID}';")
assert_eq "document status REVOKED after revoke" "REVOKED" "$DB_REV_STATUS"

AUDIT_REV=$(db_query "SELECT COUNT(*) FROM audit_events WHERE document_id = '${REV_ID}' AND event_type = 'REVOKE' AND result = 'SUCCESS';")
assert_eq "REVOKE audit event recorded" "1" "$AUDIT_REV"

POST_REV_DL=$(curl -s -o /dev/null -w "%{http_code}" "${DOWNLOAD_BASE}/download/${REV_TOKEN}")
assert_eq "download after revoke returns 410" "410" "$POST_REV_DL"

# ── Test: Rate limiting ───────────────────────────────────────────────────────
# Last: burns rate-limit budget for this IP on the download service.

echo ""
echo "=== E2E: Rate limiting ==="

RATE_LIMIT=${RATE_LIMIT_REQUESTS_PER_MINUTE:-10}

# Upload a document with enough downloads to not exhaust before the rate limit kicks in
UPLOAD_RL=$(curl -sf \
  -X POST "${MGMT_BASE}/api/v1/documents" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/pdf" \
  -H "x-capitec-max-downloads: $((RATE_LIMIT + 10))" \
  -H "x-capitec-expires-in: 60" \
  --data-binary @"$PDF")

RL_TOKEN=$(echo "$UPLOAD_RL" | grep -o '"downloadUrl":"[^"]*"' | cut -d'"' -f4 | grep -o '[^/]*$')
assert_not_empty "rate limit test doc uploaded" "$RL_TOKEN"

# Fire requests until we hit 429 or exhaust the max expected attempts
GOT_429=false
MAX_ATTEMPTS=$(( RATE_LIMIT * 2 + 5 ))
for i in $(seq 1 "${MAX_ATTEMPTS}"); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "${DOWNLOAD_BASE}/download/${RL_TOKEN}")
  if [[ "$STATUS" == "429" ]]; then
    GOT_429=true
    pass "rate limit enforced after ${i} requests (429 received)"
    break
  fi
done

if [[ "$GOT_429" == "false" ]]; then
  fail "rate limit never triggered within ${MAX_ATTEMPTS} requests"
fi

# ── Summary ───────────────────────────────────────────────────────────────────

echo ""
echo "=== Results: ${PASS} passed, ${FAIL} failed ==="

[[ $FAIL -eq 0 ]]
