# Staging Release Checklist — FirstClub Membership Platform

> **How to use this checklist**
> Complete every section in order before promoting a release candidate to production.
> Mark each row ✅ PASS, ❌ FAIL, or ⚠️ WARNING.
> Any ❌ FAIL in a **Release Blocker** row means the release must not proceed.
> Record the release candidate version, date, and sign-off at the bottom.

---

## Release Candidate Details

| Field | Value |
|---|---|
| Release candidate version | |
| Staging environment URL | |
| Date / time (UTC) | |
| Engineer running checklist | |
| Reviewer / approver | |

---

## Section 1 — Environment Setup

| # | Check | How to verify | Result | Notes |
|---|---|---|---|---|
| 1.1 | `SPRING_PROFILES_ACTIVE=prod` is set | `echo $SPRING_PROFILES_ACTIVE` on the staging host | | **Release blocker** |
| 1.2 | `JWT_SECRET` env var is set and is not the dev default | Startup log contains `[STARTUP] app=…` without any `SECURITY:` error | | **Release blocker** |
| 1.3 | `WEBHOOK_SECRET` env var is set and is not `dev-only-webhook-secret-change-in-prod` | Same startup log check | | **Release blocker** |
| 1.4 | `PII_ENC_KEY` env var is set | Startup log — no `PII_ENC_KEY` SECURITY error | | **Release blocker** |
| 1.5 | `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` all set | App starts successfully (would fail to connect otherwise) | | **Release blocker** |
| 1.6 | `app.gateway-emulator.enabled` is NOT set to `true` | Startup log — no gateway emulator SECURITY error | | **Release blocker** |
| 1.7 | Flyway migrations applied cleanly | Startup log contains no Flyway errors; DB schema version matches latest migration | | **Release blocker** |
| 1.8 | Chart of accounts seeded | Startup log: `[STARTUP] Chart of accounts present (N accounts)` with N > 0 | | **Release blocker** |

---

## Section 2 — Health and Readiness

Run these `curl` commands against `$BASE_URL` and record results.

```bash
# Replace with your staging URL
export BASE_URL="https://staging.firstclub.example.com"
```

| # | Check | Command | Expected | Result | Notes |
|---|---|---|---|---|---|
| 2.1 | Overall health UP | `curl -s $BASE_URL/actuator/health \| jq .status` | `"UP"` | | **Release blocker** |
| 2.2 | Liveness probe UP | `curl -s $BASE_URL/actuator/health/liveness \| jq .status` | `"UP"` | | **Release blocker** |
| 2.3 | Readiness probe UP | `curl -s $BASE_URL/actuator/health/readiness \| jq .status` | `"UP"` | | **Release blocker** |
| 2.4 | DB health component UP | `curl -s $BASE_URL/actuator/health \| jq '.components.db.status'` | `"UP"` | | **Release blocker** |
| 2.5 | Prometheus endpoint accessible | `curl -o /dev/null -sw "%{http_code}" $BASE_URL/actuator/prometheus` | `200` | | Warning |
| 2.6 | Info endpoint does not leak env vars | `curl -s $BASE_URL/actuator/info` | Empty `{}` or only `app.version`; no env var values | | **Release blocker** |
| 2.7 | Swagger UI disabled (prod profile) | `curl -o /dev/null -sw "%{http_code}" $BASE_URL/swagger-ui.html` | `404` or `403` | | Warning |

---

## Section 3 — Auth Flow

```bash
# Obtain admin token — replace credentials with staging values
export ADMIN_TOKEN=$(curl -sf -X POST $BASE_URL/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"'"$ADMIN_USER"'","password":"'"$ADMIN_PASS"'"}' \
  | jq -r '.accessToken')
echo "Token: ${ADMIN_TOKEN:0:20}..."
```

| # | Check | Command | Expected | Result | Notes |
|---|---|---|---|---|---|
| 3.1 | Login returns access token | See above | HTTP 200; `accessToken` is non-empty JWT | | **Release blocker** |
| 3.2 | Login returns refresh token | `… \| jq -r '.refreshToken'` | Non-empty string | | **Release blocker** |
| 3.3 | Protected endpoint accessible with valid token | `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" $BASE_URL/api/v1/users \| jq '. \| length'` | Integer ≥ 0 (HTTP 200) | | **Release blocker** |
| 3.4 | Invalid JWT rejected | `curl -o /dev/null -sw "%{http_code}" -H "Authorization: Bearer invalid.token.here" $BASE_URL/api/v1/users` | `401` | | **Release blocker** |
| 3.5 | Missing JWT rejected | `curl -o /dev/null -sw "%{http_code}" $BASE_URL/api/v1/users` | `401` or `403` | | **Release blocker** |
| 3.6 | Token refresh works | `curl -sf -X POST $BASE_URL/api/v1/auth/refresh -H "Content-Type: application/json" -d '{"refreshToken":"'"$REFRESH_TOKEN"'"}' \| jq -r '.accessToken'` | New non-empty token | | **Release blocker** |

---

## Section 4 — Payment Intent Flow

```bash
# Requires ADMIN_TOKEN from Section 3
# Set MERCHANT_ID to a valid staging merchant ID
export MERCHANT_ID=1
export IDEM_KEY="staging-smoke-$(date +%s)"
```

| # | Check | Command | Expected | Result | Notes |
|---|---|---|---|---|---|
| 4.1 | Create payment intent | `curl -sf -X POST $BASE_URL/api/v2/merchants/$MERCHANT_ID/payment-intents -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" -H "Idempotency-Key: $IDEM_KEY" -d '{"amount":1000,"currency":"INR","description":"staging smoke"}' \| jq -r '.status'` | `CREATED` | | **Release blocker** |
| 4.2 | Idempotent re-create returns same ID | Repeat 4.1 with same `Idempotency-Key`; compare `id` | Same `id` returned; no new record | | **Release blocker** |
| 4.3 | Fetch intent by ID | `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" $BASE_URL/api/v2/merchants/$MERCHANT_ID/payment-intents/$INTENT_ID \| jq -r '.status'` | `CREATED` (or current status) | | Warning |
| 4.4 | `requestId` present in response headers | `curl -sI -H "Authorization: Bearer $ADMIN_TOKEN" $BASE_URL/api/v2/merchants/$MERCHANT_ID/payment-intents/$INTENT_ID \| grep -i x-request-id` | Header present | | Warning |

---

## Section 5 — Webhook Ingestion Flow

```bash
# Requires WEBHOOK_SECRET env var
# Build a minimal webhook payload and sign it
export WH_PAYLOAD='{"eventId":"staging-evt-001","eventType":"PAYMENT_INTENT.SUCCEEDED","paymentIntentId":1,"amount":1000,"currency":"INR","gatewayTxnId":"gw-staging-001","timestamp":"2026-01-01T00:00:00"}'
export WH_SIG=$(echo -n "$WH_PAYLOAD" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" | awk '{print $2}')
```

| # | Check | Command | Expected | Result | Notes |
|---|---|---|---|---|---|
| 5.1 | Valid signature accepted | `curl -sf -X POST $BASE_URL/api/v1/webhooks/gateway -H "Content-Type: application/json" -H "X-Signature: $WH_SIG" -d "$WH_PAYLOAD" \| jq -r '.result'` | `PROCESSED` | | **Release blocker** |
| 5.2 | Duplicate event returns DUPLICATE | Repeat 5.1 with same payload | `DUPLICATE` | | **Release blocker** |
| 5.3 | Invalid signature rejected | `curl -o /dev/null -sw "%{http_code}" -X POST $BASE_URL/api/v1/webhooks/gateway -H "Content-Type: application/json" -H "X-Signature: badsig" -d "$WH_PAYLOAD"` | `401` | | **Release blocker** |
| 5.4 | Missing signature rejected | `curl -o /dev/null -sw "%{http_code}" -X POST $BASE_URL/api/v1/webhooks/gateway -H "Content-Type: application/json" -d "$WH_PAYLOAD"` | `401` | | **Release blocker** |

---

## Section 6 — Refund Flow

```bash
# Requires a CAPTURED payment in staging
# Set PAYMENT_ID to a known captured payment in the staging DB
export PAYMENT_ID=<captured-payment-id>
export REFUND_IDEM_KEY="staging-refund-$(date +%s)"
```

| # | Check | Command | Expected | Result | Notes |
|---|---|---|---|---|---|
| 6.1 | Partial refund succeeds | `curl -sf -X POST $BASE_URL/api/v2/merchants/$MERCHANT_ID/payments/$PAYMENT_ID/refunds -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" -H "Idempotency-Key: $REFUND_IDEM_KEY" -d '{"amount":100,"reason":"staging smoke test"}' \| jq -r '.status'` | Refund status field present (not null) | | **Release blocker** |
| 6.2 | Idempotent retry returns same refund | Repeat 6.1 with same `Idempotency-Key` | Same refund `id` returned; no second record | | **Release blocker** |
| 6.3 | Over-refund blocked | Same endpoint with `amount` greater than refundable amount | HTTP 422; error body | | **Release blocker** |
| 6.4 | Refundable amount endpoint works | `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/api/v2/admin/payments/$PAYMENT_ID/refund-capacity" \| jq .` | JSON with `refundableAmount` field | | Warning |

---

## Section 7 — Renewal and Dunning Scheduler Safety

These checks are log-based and DB-state-based. Run after the app has been up for at least one scheduler cycle (5–10 minutes).

| # | Check | How to verify | Expected | Result | Notes |
|---|---|---|---|---|---|
| 7.1 | Scheduler health endpoint returns data | `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" $BASE_URL/api/v2/admin/schedulers/health \| jq .` | HTTP 200; JSON array with at least `subscription-renewal` and `dunning-v1` entries | | **Release blocker** |
| 7.2 | No scheduler stuck in STALE | Inspect output from 7.1 | No entry with `status=STALE` (acceptable: `HEALTHY` or `NEVER_RAN` on fresh deploy) | | Warning |
| 7.3 | Advisory lock log present | Grep app logs: `grep "SCHEDULER-LOCK" <log>` | Lines like `Acquired batch lock scheduler=subscription-renewal` or `Lock busy — skipping` | | Warning |
| 7.4 | Primary-only guard log present (multi-node) | Grep app logs: `grep "not-primary skip"` | Only applicable in multi-node staging; secondary node shows skip lines | | Warning |
| 7.5 | Renewal scheduler ran at least once | Check DB: `SELECT COUNT(*) FROM payment_attempts WHERE created_at > now() - interval '10 minutes'` OR inspect logs for `Renewal scheduler: N subscription(s) due` | At least one execution logged | | Warning |
| 7.6 | Dunning scheduler ran at least once | Grep logs for `[dunning-v1] Scheduler tick` | At least one execution logged | | Warning |

---

## Section 8 — Observability

| # | Check | How to verify | Expected | Result | Notes |
|---|---|---|---|---|---|
| 8.1 | Deep health endpoint (enhanced) | `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" $BASE_URL/ops/health/deep \| jq '{schedulerHealth: .schedulerHealth, projectionLag: .projectionLag}'` | Both fields present and non-null | | Warning |
| 8.2 | System summary endpoint | `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" $BASE_URL/api/v2/admin/system/health/deep \| jq .` | HTTP 200; outbox/DLQ/webhook counters all present | | Warning |
| 8.3 | SLO status endpoint | `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" $BASE_URL/ops/slo/status \| jq .` | HTTP 200; SLO entries present | | Warning |
| 8.4 | JSON log format confirmed | Tail app logs; pipe one line through `jq .` | Parses as valid JSON with `@timestamp`, `level`, `message`, `requestId` fields | | Warning |
| 8.5 | `requestId` in API response header | `curl -sI -H "Authorization: Bearer $ADMIN_TOKEN" $BASE_URL/api/v1/users \| grep -i x-request-id` | `X-Request-Id` header is present | | Warning |
| 8.6 | Prometheus counter incremented after payment | Compare `payment_success_total` before/after a confirm: `curl -s $BASE_URL/actuator/prometheus \| grep payment_success_total` | Counter value increases after each successful payment | | Warning |

---

## Section 9 — Startup / Fail-Fast Regression

> These tests require temporarily restarting the app with bad config. Run in a separate staging instance or as a pre-deploy check before the main deployment.

| # | Check | How to test | Expected | Result | Notes |
|---|---|---|---|---|---|
| 9.1 | Missing `WEBHOOK_SECRET` causes fail-fast | Start with `WEBHOOK_SECRET` unset (or set to `dev-only-webhook-secret-change-in-prod`) while `SPRING_PROFILES_ACTIVE=prod` | App exits at startup; log: `[STARTUP] SECURITY: payments.webhook.secret is the insecure dev placeholder` | | **Release blocker** |
| 9.2 | Missing `JWT_SECRET` causes fail-fast | Start with `JWT_SECRET` unset or set to dev default | App exits at startup; log: `[STARTUP] SECURITY: app.jwt.secret is the insecure dev-only default` | | **Release blocker** |
| 9.3 | Missing `PII_ENC_KEY` causes fail-fast | Start without `PII_ENC_KEY` env var | App exits at startup; log: `[STARTUP] SECURITY: PII_ENC_KEY environment variable is not set` | | **Release blocker** |
| 9.4 | Gateway emulator blocked in prod profile | Start with `app.gateway-emulator.enabled=true` and `SPRING_PROFILES_ACTIVE=prod` | App exits at startup; log: `[STARTUP] SECURITY: app.gateway-emulator.enabled=true` | | **Release blocker** |

---

## Final Sign-off

| Item | Status |
|---|---|
| All **Release Blocker** rows are ✅ PASS | |
| All Warning rows reviewed and accepted (or remediated) | |
| Smoke script `scripts/staging-smoke.sh` exited 0 | |
| Release owner has reviewed and accepted known issues | |

| Role | Name | Signature / Date |
|---|---|---|
| Engineer running checklist | | |
| Release approver | | |

---

### Known Issues at Time of Checklist

| Issue | Severity | Accepted by | Date |
|---|---|---|---|
| `PaymentIntentV2ServiceImpl.confirmPaymentIntent` hardcodes `"DECLINED"` as gateway status code on failure (line 279) — actual gateway code not propagated to the failure record | Warning | | |

