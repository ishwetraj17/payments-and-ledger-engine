#!/usr/bin/env bash
# =============================================================================
# staging-smoke.sh — FirstClub Membership Platform staging smoke verification
# =============================================================================
#
# Validates all critical staging flows before release:
#   1. Startup / config (via health probe)
#   2. Auth (login, protected access, invalid token)
#   3. Payment intent (create, idempotency)
#   4. Webhook ingestion (valid sig, invalid sig, duplicate)
#   5. Refund V2 (relies on a captured payment in staging DB)
#   6. Observability (health, readiness, prometheus, deep health, schedulers)
#
# Usage:
#   export BASE_URL="https://staging.firstclub.example.com"
#   export ADMIN_USER="admin"
#   export ADMIN_PASS="<your-admin-password>"
#   export WEBHOOK_SECRET="<your-WEBHOOK_SECRET>"
#   export MERCHANT_ID=1           # optional, defaults to 1
#   export CAPTURED_PAYMENT_ID=""  # optional; refund steps skipped if unset
#   ./scripts/staging-smoke.sh
#
# Exit codes:
#   0  — all critical checks passed
#   1  — one or more critical checks failed
#
# Safe by default:
#   - No secrets are embedded in this script
#   - All sensitive values are read from env vars
#   - Read-only or idempotent operations only (no destructive mutations)
# =============================================================================
set -euo pipefail
IFS=$'\n\t'

# ─── Env defaults ─────────────────────────────────────────────────────────────
BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin123}"
WEBHOOK_SECRET="${WEBHOOK_SECRET:-}"
MERCHANT_ID="${MERCHANT_ID:-1}"
CAPTURED_PAYMENT_ID="${CAPTURED_PAYMENT_ID:-}"

# ─── Counters ─────────────────────────────────────────────────────────────────
PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0

# ─── Colour helpers ───────────────────────────────────────────────────────────
GREEN="\033[1;32m"
RED="\033[1;31m"
YELLOW="\033[1;33m"
CYAN="\033[1;36m"
RESET="\033[0m"

header() { echo -e "\n${CYAN}━━━ $* ━━━${RESET}"; }
pass()   { echo -e "${GREEN}  ✓ PASS${RESET}  $*"; PASS_COUNT=$((PASS_COUNT + 1)); }
fail()   { echo -e "${RED}  ✗ FAIL${RESET}  $*"; FAIL_COUNT=$((FAIL_COUNT + 1)); }
warn()   { echo -e "${YELLOW}  ⚠ WARN${RESET}  $*"; WARN_COUNT=$((WARN_COUNT + 1)); }
info()   { echo -e "         $*"; }

# ─── Dependency check ─────────────────────────────────────────────────────────
for cmd in curl jq openssl; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "ERROR: Required tool '$cmd' is not installed." >&2
        exit 1
    fi
done

# ─── Helper: HTTP request with status code capture ────────────────────────────
# Usage: http_get <url> [extra curl args...]
# Returns body on stdout; puts HTTP status in $HTTP_STATUS
http_get() {
    local url="$1"; shift
    HTTP_BODY=$(curl -s -o /tmp/smoke_body.json -w "%{http_code}" "$url" "$@" 2>/dev/null || true)
    HTTP_STATUS="$HTTP_BODY"
    HTTP_BODY=$(cat /tmp/smoke_body.json 2>/dev/null || true)
}

# Usage: http_post <url> <body> [extra curl args...]
http_post() {
    local url="$1" body="$2"; shift 2
    HTTP_STATUS=$(curl -s -o /tmp/smoke_body.json -w "%{http_code}" -X POST \
        -H "Content-Type: application/json" \
        -d "$body" "$url" "$@" 2>/dev/null || true)
    HTTP_BODY=$(cat /tmp/smoke_body.json 2>/dev/null || true)
}

# ─── Globals populated during test run ────────────────────────────────────────
ADMIN_TOKEN=""
REFRESH_TOKEN=""
INTENT_ID=""

# =============================================================================
# Section 1 — Health / Startup Validation
# =============================================================================
header "1. Health / Startup Validation"

http_get "$BASE_URL/actuator/health"
STATUS=$(echo "$HTTP_BODY" | jq -r '.status' 2>/dev/null || echo "")
if [[ "$HTTP_STATUS" == "200" && "$STATUS" == "UP" ]]; then
    pass "Overall health is UP"
else
    fail "Overall health check failed (HTTP $HTTP_STATUS, status=$STATUS)"
fi

http_get "$BASE_URL/actuator/health/liveness"
STATUS=$(echo "$HTTP_BODY" | jq -r '.status' 2>/dev/null || echo "")
if [[ "$HTTP_STATUS" == "200" && "$STATUS" == "UP" ]]; then
    pass "Liveness probe is UP"
else
    fail "Liveness probe failed (HTTP $HTTP_STATUS, status=$STATUS)"
fi

http_get "$BASE_URL/actuator/health/readiness"
STATUS=$(echo "$HTTP_BODY" | jq -r '.status' 2>/dev/null || echo "")
if [[ "$HTTP_STATUS" == "200" && "$STATUS" == "UP" ]]; then
    pass "Readiness probe is UP"
else
    fail "Readiness probe failed (HTTP $HTTP_STATUS, status=$STATUS)"
fi

http_get "$BASE_URL/actuator/health"
DB_STATUS=$(echo "$HTTP_BODY" | jq -r '.components.db.status' 2>/dev/null || echo "")
if [[ "$DB_STATUS" == "UP" ]]; then
    pass "Database health component is UP"
else
    warn "Database health component status='$DB_STATUS' (may be hidden by when-authorized setting)"
fi

http_get "$BASE_URL/actuator/prometheus"
if [[ "$HTTP_STATUS" == "200" ]]; then
    pass "Prometheus metrics endpoint accessible (HTTP 200)"
else
    warn "Prometheus endpoint returned HTTP $HTTP_STATUS"
fi

http_get "$BASE_URL/actuator/info"
ENV_LEAK=$(echo "$HTTP_BODY" | jq 'keys | length' 2>/dev/null || echo "0")
if [[ "$HTTP_STATUS" == "200" && "${ENV_LEAK:-0}" -le 2 ]]; then
    pass "Actuator /info does not expose sensitive data (${ENV_LEAK} key(s))"
else
    warn "Actuator /info response has $ENV_LEAK keys — review for env var leaks"
fi

# =============================================================================
# Section 2 — Auth Flow
# =============================================================================
header "2. Auth Flow"

LOGIN_BODY=$(jq -nc --arg u "$ADMIN_USER" --arg p "$ADMIN_PASS" \
    '{"username":$u,"password":$p}')
http_post "$BASE_URL/api/v1/auth/login" "$LOGIN_BODY"
ADMIN_TOKEN=$(echo "$HTTP_BODY" | jq -r '.accessToken' 2>/dev/null || echo "")
REFRESH_TOKEN=$(echo "$HTTP_BODY" | jq -r '.refreshToken' 2>/dev/null || echo "")

if [[ "$HTTP_STATUS" == "200" && -n "$ADMIN_TOKEN" && "$ADMIN_TOKEN" != "null" ]]; then
    pass "Admin login succeeded; access token received"
else
    fail "Admin login failed (HTTP $HTTP_STATUS) — check ADMIN_USER / ADMIN_PASS"
    info "Body: $HTTP_BODY"
    echo -e "\n${RED}Cannot continue without a valid admin token. Aborting.${RESET}"
    exit 1
fi

if [[ -n "$REFRESH_TOKEN" && "$REFRESH_TOKEN" != "null" ]]; then
    pass "Refresh token present in login response"
else
    fail "Refresh token missing in login response"
fi

# Protected endpoint with valid token
http_get "$BASE_URL/api/v1/users" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
if [[ "$HTTP_STATUS" == "200" ]]; then
    pass "Protected endpoint accessible with valid JWT"
else
    fail "Protected endpoint returned HTTP $HTTP_STATUS with valid JWT"
fi

# Invalid token rejected
http_get "$BASE_URL/api/v1/users" \
    -H "Authorization: Bearer invalid.token.here"
if [[ "$HTTP_STATUS" == "401" ]]; then
    pass "Invalid JWT correctly rejected (HTTP 401)"
else
    fail "Invalid JWT was NOT rejected (HTTP $HTTP_STATUS — expected 401)"
fi

# Missing token rejected
http_get "$BASE_URL/api/v1/users"
if [[ "$HTTP_STATUS" == "401" || "$HTTP_STATUS" == "403" ]]; then
    pass "Missing JWT correctly rejected (HTTP $HTTP_STATUS)"
else
    fail "Missing JWT was NOT rejected (HTTP $HTTP_STATUS — expected 401/403)"
fi

# Token refresh
if [[ -n "$REFRESH_TOKEN" && "$REFRESH_TOKEN" != "null" ]]; then
    REFRESH_BODY=$(jq -nc --arg rt "$REFRESH_TOKEN" '{"refreshToken":$rt}')
    http_post "$BASE_URL/api/v1/auth/refresh" "$REFRESH_BODY"
    NEW_TOKEN=$(echo "$HTTP_BODY" | jq -r '.accessToken' 2>/dev/null || echo "")
    if [[ "$HTTP_STATUS" == "200" && -n "$NEW_TOKEN" && "$NEW_TOKEN" != "null" ]]; then
        pass "Token refresh succeeded; new access token received"
    else
        fail "Token refresh failed (HTTP $HTTP_STATUS)"
    fi
else
    warn "Skipping token refresh check — no refresh token available"
fi

# =============================================================================
# Section 3 — Payment Intent Flow
# =============================================================================
header "3. Payment Intent Flow"

IDEM_KEY="staging-smoke-pi-$(date +%s)"
INTENT_BODY=$(jq -nc \
    '{"amount":100,"currency":"INR","description":"staging smoke test"}')

http_post "$BASE_URL/api/v2/merchants/$MERCHANT_ID/payment-intents" \
    "$INTENT_BODY" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Idempotency-Key: $IDEM_KEY"
INTENT_ID=$(echo "$HTTP_BODY" | jq -r '.id' 2>/dev/null || echo "")
INTENT_STATUS=$(echo "$HTTP_BODY" | jq -r '.status' 2>/dev/null || echo "")

if [[ "$HTTP_STATUS" == "201" && -n "$INTENT_ID" && "$INTENT_ID" != "null" ]]; then
    pass "Payment intent created (id=$INTENT_ID, status=$INTENT_STATUS)"
else
    fail "Payment intent creation failed (HTTP $HTTP_STATUS)"
    info "Body: $HTTP_BODY"
fi

# Idempotency — repeat with same key
if [[ -n "$INTENT_ID" && "$INTENT_ID" != "null" ]]; then
    http_post "$BASE_URL/api/v2/merchants/$MERCHANT_ID/payment-intents" \
        "$INTENT_BODY" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -H "Idempotency-Key: $IDEM_KEY"
    IDEM_ID=$(echo "$HTTP_BODY" | jq -r '.id' 2>/dev/null || echo "")
    if [[ "$IDEM_ID" == "$INTENT_ID" ]]; then
        pass "Payment intent creation is idempotent (same id=$IDEM_ID on repeat)"
    else
        fail "Idempotency broken: first id=$INTENT_ID, repeat id=$IDEM_ID"
    fi
fi

# Fetch intent
if [[ -n "$INTENT_ID" && "$INTENT_ID" != "null" ]]; then
    http_get "$BASE_URL/api/v2/merchants/$MERCHANT_ID/payment-intents/$INTENT_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN"
    FETCHED_STATUS=$(echo "$HTTP_BODY" | jq -r '.status' 2>/dev/null || echo "")
    if [[ "$HTTP_STATUS" == "200" && -n "$FETCHED_STATUS" ]]; then
        pass "Payment intent fetch succeeded (status=$FETCHED_STATUS)"
    else
        warn "Payment intent fetch returned HTTP $HTTP_STATUS"
    fi
fi

# =============================================================================
# Section 4 — Webhook Ingestion Flow
# =============================================================================
header "4. Webhook Ingestion Flow"

if [[ -z "$WEBHOOK_SECRET" ]]; then
    warn "WEBHOOK_SECRET env var not set — skipping webhook signature tests"
else
    WH_EVENT_ID="staging-smoke-wh-$(date +%s)"
    WH_PAYLOAD=$(jq -nc \
        --arg eid "$WH_EVENT_ID" \
        --argjson pid "${INTENT_ID:-1}" \
        '{
            "eventId": $eid,
            "eventType": "PAYMENT_INTENT.SUCCEEDED",
            "paymentIntentId": $pid,
            "amount": 100,
            "currency": "INR",
            "gatewayTxnId": ("gw-staging-" + $eid),
            "timestamp": "2026-01-01T00:00:00"
        }')

    # Compute HMAC-SHA256 signature
    WH_SIG=$(printf '%s' "$WH_PAYLOAD" | \
        openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" 2>/dev/null | \
        awk '{print $2}')

    # Valid signature — should be PROCESSED
    http_post "$BASE_URL/api/v1/webhooks/gateway" "$WH_PAYLOAD" \
        -H "X-Signature: $WH_SIG"
    WH_RESULT=$(echo "$HTTP_BODY" | jq -r '.result' 2>/dev/null || echo "")
    if [[ "$HTTP_STATUS" == "200" && ("$WH_RESULT" == "PROCESSED" || "$WH_RESULT" == "DUPLICATE") ]]; then
        pass "Valid webhook signature accepted (result=$WH_RESULT)"
    else
        fail "Valid webhook was not accepted (HTTP $HTTP_STATUS, result=$WH_RESULT)"
        info "Body: $HTTP_BODY"
    fi

    # Duplicate event — should be DUPLICATE
    http_post "$BASE_URL/api/v1/webhooks/gateway" "$WH_PAYLOAD" \
        -H "X-Signature: $WH_SIG"
    WH_RESULT2=$(echo "$HTTP_BODY" | jq -r '.result' 2>/dev/null || echo "")
    if [[ "$HTTP_STATUS" == "200" && "$WH_RESULT2" == "DUPLICATE" ]]; then
        pass "Duplicate webhook event correctly deduplicated (result=DUPLICATE)"
    else
        warn "Duplicate webhook response: HTTP $HTTP_STATUS, result=$WH_RESULT2 (expected DUPLICATE)"
    fi

    # Invalid signature — should be 401
    http_post "$BASE_URL/api/v1/webhooks/gateway" "$WH_PAYLOAD" \
        -H "X-Signature: 0000000000000000000000000000000000000000000000000000000000000000"
    if [[ "$HTTP_STATUS" == "401" ]]; then
        pass "Invalid webhook signature correctly rejected (HTTP 401)"
    else
        fail "Invalid webhook signature was NOT rejected (HTTP $HTTP_STATUS — expected 401)"
    fi

    # Missing signature — should be 401
    http_post "$BASE_URL/api/v1/webhooks/gateway" "$WH_PAYLOAD"
    if [[ "$HTTP_STATUS" == "401" ]]; then
        pass "Missing webhook signature correctly rejected (HTTP 401)"
    else
        fail "Missing webhook signature was NOT rejected (HTTP $HTTP_STATUS — expected 401)"
    fi
fi

# =============================================================================
# Section 5 — Refund Flow
# =============================================================================
header "5. Refund Flow"

if [[ -z "$CAPTURED_PAYMENT_ID" ]]; then
    warn "CAPTURED_PAYMENT_ID env var not set — skipping refund flow checks"
    info "Set CAPTURED_PAYMENT_ID to a known CAPTURED payment ID in the staging DB to enable this section."
else
    REFUND_IDEM_KEY="staging-smoke-refund-$(date +%s)"
    REFUND_BODY=$(jq -nc '{"amount":1,"reason":"staging smoke test"}')

    http_post "$BASE_URL/api/v2/merchants/$MERCHANT_ID/payments/$CAPTURED_PAYMENT_ID/refunds" \
        "$REFUND_BODY" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -H "Idempotency-Key: $REFUND_IDEM_KEY"
    REFUND_ID=$(echo "$HTTP_BODY" | jq -r '.id' 2>/dev/null || echo "")
    if [[ "$HTTP_STATUS" == "201" && -n "$REFUND_ID" && "$REFUND_ID" != "null" ]]; then
        pass "Partial refund created (id=$REFUND_ID)"
    else
        fail "Partial refund failed (HTTP $HTTP_STATUS)"
        info "Body: $HTTP_BODY"
    fi

    # Idempotent retry
    if [[ -n "$REFUND_ID" && "$REFUND_ID" != "null" ]]; then
        http_post "$BASE_URL/api/v2/merchants/$MERCHANT_ID/payments/$CAPTURED_PAYMENT_ID/refunds" \
            "$REFUND_BODY" \
            -H "Authorization: Bearer $ADMIN_TOKEN" \
            -H "Idempotency-Key: $REFUND_IDEM_KEY"
        IDEM_REFUND_ID=$(echo "$HTTP_BODY" | jq -r '.id' 2>/dev/null || echo "")
        if [[ "$IDEM_REFUND_ID" == "$REFUND_ID" ]]; then
            pass "Refund creation is idempotent (same id=$IDEM_REFUND_ID on repeat)"
        else
            fail "Refund idempotency broken: first id=$REFUND_ID, repeat id=$IDEM_REFUND_ID"
        fi
    fi

    # Refund capacity endpoint
    http_get "$BASE_URL/api/v2/admin/payments/$CAPTURED_PAYMENT_ID/refund-capacity" \
        -H "Authorization: Bearer $ADMIN_TOKEN"
    if [[ "$HTTP_STATUS" == "200" ]]; then
        REFUNDABLE=$(echo "$HTTP_BODY" | jq -r '.refundableAmount' 2>/dev/null || echo "")
        pass "Refund capacity endpoint accessible (refundableAmount=$REFUNDABLE)"
    else
        warn "Refund capacity endpoint returned HTTP $HTTP_STATUS"
    fi
fi

# =============================================================================
# Section 6 — Observability
# =============================================================================
header "6. Observability"

# Deep health (enhanced — requires ADMIN)
http_get "$BASE_URL/ops/health/deep" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
if [[ "$HTTP_STATUS" == "200" ]]; then
    pass "Enhanced deep health endpoint accessible (HTTP 200)"
else
    warn "Enhanced deep health returned HTTP $HTTP_STATUS"
fi

# System summary
http_get "$BASE_URL/api/v2/admin/system/health/deep" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
if [[ "$HTTP_STATUS" == "200" ]]; then
    pass "System deep health summary accessible (HTTP 200)"
else
    warn "System deep health returned HTTP $HTTP_STATUS"
fi

# Scheduler health
http_get "$BASE_URL/api/v2/admin/schedulers/health" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
if [[ "$HTTP_STATUS" == "200" ]]; then
    STALE_COUNT=$(echo "$HTTP_BODY" | jq '[.[] | select(.status == "STALE")] | length' 2>/dev/null || echo "0")
    if [[ "${STALE_COUNT:-0}" -gt 0 ]]; then
        warn "Scheduler health: $STALE_COUNT scheduler(s) in STALE state — review before release"
    else
        pass "Scheduler health accessible; no STALE schedulers detected"
    fi
else
    warn "Scheduler health endpoint returned HTTP $HTTP_STATUS"
fi

# X-Request-Id header in responses
HEADERS=$(curl -sI \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/users" 2>/dev/null || true)
if echo "$HEADERS" | grep -qi "x-request-id"; then
    pass "X-Request-Id correlation header present in API responses"
else
    warn "X-Request-Id header not found in response headers — check RequestIdFilter"
fi

# =============================================================================
# Summary
# =============================================================================
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Staging Smoke Results"
echo "  URL: $BASE_URL"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "  ${GREEN}PASS${RESET}: $PASS_COUNT"
echo -e "  ${RED}FAIL${RESET}: $FAIL_COUNT"
echo -e "  ${YELLOW}WARN${RESET}: $WARN_COUNT"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [[ "$FAIL_COUNT" -gt 0 ]]; then
    echo -e "\n${RED}❌ STAGING SMOKE FAILED — $FAIL_COUNT check(s) did not pass.${RESET}"
    echo "   Do NOT promote this release candidate to production."
    exit 1
else
    echo -e "\n${GREEN}✅ STAGING SMOKE PASSED — all critical checks passed.${RESET}"
    if [[ "$WARN_COUNT" -gt 0 ]]; then
        echo -e "   ${YELLOW}$WARN_COUNT warning(s) noted — review before release.${RESET}"
    fi
    exit 0
fi
