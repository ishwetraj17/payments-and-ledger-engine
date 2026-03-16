# Staging Test Plan — FirstClub Membership Platform

> **Step 6 — Staging Validation**
> This plan is the authoritative pre-release staging checklist.
> It is grounded in the actual repository endpoints, classes, and scripts.
> Run it in full before every release candidate is promoted to production.

---

## Phase 0 — Inventory

| Artifact | Path | Purpose | Reusable for staging? | Gap |
|---|---|---|---|---|
| Dev-up script | `scripts/dev-up.sh` | Starts Docker Compose (Postgres + PgAdmin) | Yes | Targets local only; no staging env vars |
| Dev-down script | `scripts/dev-down.sh` | Stops Docker Compose | Yes | Local only |
| Run-local script | `scripts/run-local.sh` | Runs Spring Boot with `local` profile | Partial | Uses localhost; staging needs `prod` profile |
| Demo walkthrough | `scripts/demo.sh` | Full 2-minute curl-based API walkthrough | Partial | Not a structured smoke test; no pass/fail |
| API test suite | `master_api_tests.py` | Python HTTP test suite (CRUD + business logic) | Partial | Not structured for staging; no idempotency/webhook/scheduler coverage |
| Stress test | `stress_test.py` | Multi-threaded load simulation | No | Not a correctness test |
| User stress test | `user_stress_test.py` | User-scoped load simulation | No | Not a correctness test |
| k6 payment intent burst | `load-tests/k6/payment_intent_burst.js` | Load test for intent creation | No | Load/perf only; not a correctness smoke |
| k6 confirm burst | `load-tests/k6/payment_confirm_burst.js` | Load test for confirm flow | No | Load/perf only |
| k6 webhook storm | `load-tests/k6/webhook_duplicate_storm.js` | Dedup stress under duplicate flood | No | Load/perf only |
| k6 outbox throughput | `load-tests/k6/outbox_backlog_throughput.js` | Outbox fill + drain | No | Load/perf only |
| k6 projection rebuild | `load-tests/k6/projection_rebuild_synthetic.js` | Projection rebuild impact | No | Load/perf only |
| Env example | `.env.example` | Documents all required env vars with staging notes | Yes | No staging-specific `.env.staging.example` |
| App config (dev) | `src/main/resources/application-dev.properties` | H2 in-memory, no Flyway | No | Not for staging |
| App config (local) | `src/main/resources/application-local.properties` | PostgreSQL, Flyway, dev JWT | Partial | Uses dev JWT fallback; needs real secrets |
| App config (prod) | `src/main/resources/application-prod.properties` | PostgreSQL, strict secrets, Flyway | Yes | Use with staging env vars (profile = `prod`) |
| Startup validator | `src/main/java/…/ops/startup/StartupValidationRunner.java` | Fail-fast on missing secrets, dev placeholders | Yes | Already enforces staging constraints |
| Auth controller | `src/main/java/…/membership/controller/AuthController.java` | `POST /api/v1/auth/login`, `/register`, `/refresh`, `/logout` | Yes | No targeted staging scenario test |
| Payment intent controller | `src/main/java/…/payments/controller/PaymentIntentV2Controller.java` | `POST /api/v2/merchants/{id}/payment-intents` + confirm | Yes | No staging smoke; `confirmPaymentIntent` hardcodes "DECLINED" on failure |
| Webhook controller | `src/main/java/…/payments/controller/WebhookController.java` | `POST /api/v1/webhooks/gateway` with HMAC-SHA256 | Yes | No negative-signature staging test |
| Refund controller V2 | `src/main/java/…/payments/refund/controller/RefundControllerV2.java` | `POST /api/v2/merchants/{mid}/payments/{pid}/refunds` | Yes | No idempotent-retry staging test |
| Renewal scheduler | `src/main/java/…/dunning/scheduler/RenewalScheduler.java` | Every 5 min; primary-only + advisory lock | Yes | No staging trigger / verification step |
| Dunning scheduler | `src/main/java/…/dunning/scheduler/DunningScheduler.java` | Every 10 min; primary-only + advisory lock | Yes | No staging trigger / verification step |
| Scheduler ops endpoint | `src/main/java/…/scheduler/health/SchedulerOpsController.java` | `GET /api/v2/admin/schedulers/health` | Yes | Not in the staging verification runbook |
| Deep health controller | `src/main/java/…/platform/health/DeepHealthController.java` | `GET /ops/health/deep`, `GET /ops/slo/status` | Yes | Not in the staging verification runbook |
| Ops admin controller | `src/main/java/…/platform/ops/controller/OpsAdminController.java` | `GET /api/v2/admin/system/health/deep`, `/summary` | Yes | Not in the staging verification runbook |
| Actuator health | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Standard Spring Boot probes | Yes | Not documented in staging runbook |
| Prometheus endpoint | `/actuator/prometheus` | Metrics scrape endpoint | Yes | Not verified as part of staging |
| Observability doc | `docs/operations/11-observability-and-slos.md` | Full metrics/SLO reference | Yes | No staging validation instructions |
| Scheduler runbook | `docs/operations/07-scheduler-runbook.md` | Scheduler operation guide | Yes | No staging verification steps |
| Logback config | `src/main/resources/logback-spring.xml` | JSON structured logs with `requestId`, `requestPath`, `httpStatus`, `latencyMs` | Yes | Not verified as part of staging |
| Staging smoke script | `scripts/staging-smoke.sh` | **Missing** — targeted smoke test for staging | **No** | **Must be created** |
| Staging release checklist | `docs/operations/staging-release-checklist.md` | **Missing** — operator pre-release checklist | **No** | **Must be created** |

---

## Phase 1 — Staging Validation Matrix

### 1. Auth Flow

| | |
|---|---|
| **Class** | `AuthController` (`/api/v1/auth`) |

| Sub-flow | Trigger | Preconditions | Expected state change | Expected evidence | Failure signal | Severity |
|---|---|---|---|---|---|---|
| Login — happy path | `POST /api/v1/auth/login` with valid credentials | Admin user seeded (see `.env.example` or seed data) | Returns `accessToken` + `refreshToken` | HTTP 200; `accessToken` is a valid JWT; log entry with `requestId` present | HTTP 401 or missing token fields | **Release blocker** |
| Protected endpoint — valid JWT | `GET /api/v1/users` with `Authorization: Bearer <token>` | Admin user logged in | Returns user list | HTTP 200 | HTTP 401/403 | **Release blocker** |
| Invalid JWT rejected | `GET /api/v1/users` with forged/expired token | Any state | Request rejected | HTTP 401; error body with `errorCode` field | HTTP 200 (bypass) | **Release blocker** |
| Token refresh | `POST /api/v1/auth/refresh` with valid refresh token | Logged-in session | New `accessToken` issued | HTTP 200; new token != old token | HTTP 401/403 | **Release blocker** |
| Logout / blacklist | `POST /api/v1/auth/logout` with valid token, then retry `GET /api/v1/users` | Any logged-in user | Second request rejected | HTTP 401 on retry | HTTP 200 on retry (blacklist bypass) | **Release blocker** |

---

### 2. Payment Intent Flow

| | |
|---|---|
| **Class** | `PaymentIntentV2Controller` (`/api/v2/merchants/{merchantId}/payment-intents`) |
| **Note** | `confirmPaymentIntent` in `PaymentIntentV2ServiceImpl` hardcodes `"DECLINED"` as the gateway status code on failure (line 279). Staging must verify the gateway result propagates correctly when using real gateway responses. |

| Sub-flow | Trigger | Preconditions | Expected state change | Expected evidence | Failure signal | Severity |
|---|---|---|---|---|---|---|
| Create intent | `POST /api/v2/merchants/{mid}/payment-intents` with `Idempotency-Key` | Merchant exists; ADMIN role | Intent created in `CREATED` state | HTTP 201; `id` returned; `status=CREATED` | HTTP 400/404/500 | **Release blocker** |
| Confirm — success path | `POST /…/{id}/confirm` with valid payment method | Intent in `CREATED` state | Intent transitions to `SUCCEEDED`; payment record created | HTTP 200; `status=SUCCEEDED`; `payment_success_total` counter incremented in `/actuator/prometheus` | Intent stays `CREATED` or returns error | **Release blocker** |
| Confirm — failure path | `POST /…/{id}/confirm` with a payment method configured to fail | Intent in `CREATED` state | Intent transitions to `FAILED`; `failureCode` populated | HTTP 200; `status=FAILED`; `payment_failed_total` counter incremented | Missing `failureCode`; wrong status | Warning |
| Idempotency — duplicate create | Repeat `POST` with same `Idempotency-Key` | Intent already created | Original intent returned, no new record | HTTP 201 (or 200); same `id`; no duplicate row | Two records created | **Release blocker** |
| Idempotency — duplicate confirm | Repeat `POST /confirm` for already-succeeded intent | Intent in `SUCCEEDED` state | Snapshot returned, no re-processing | HTTP 200; `status=SUCCEEDED` | Duplicate payment attempt | **Release blocker** |
| Fetch intent | `GET /api/v2/merchants/{mid}/payment-intents/{id}` | Intent exists | Current state returned | HTTP 200 with all fields | HTTP 404 | Warning |

---

### 3. Webhook Ingestion Flow

| | |
|---|---|
| **Class** | `WebhookController` (`/api/v1/webhooks/gateway`); `WebhookProcessingService`; `WebhookSignatureService` |
| **Signature** | `X-Signature: hex(HMAC-SHA256(payload, WEBHOOK_SECRET))` — compute with `openssl dgst -sha256 -hmac "$WEBHOOK_SECRET"` |

| Sub-flow | Trigger | Preconditions | Expected state change | Expected evidence | Failure signal | Severity |
|---|---|---|---|---|---|---|
| Valid signature accepted | `POST /api/v1/webhooks/gateway` with correct `X-Signature` | App running; `WEBHOOK_SECRET` set | Event stored + processed; payment intent status updated | HTTP 200; `{"status":"OK","result":"PROCESSED"}`; `webhook_processed_total` counter incremented | HTTP 401 or `INVALID_SIGNATURE` result | **Release blocker** |
| Invalid signature rejected | Same endpoint with wrong/missing `X-Signature` | Any state | Event stored (audit) but not processed | HTTP 401; `{"status":"ERROR","result":"INVALID_SIGNATURE"}` | HTTP 200 (signature bypass) | **Release blocker** |
| Duplicate event ignored | Repeat `POST` with same `eventId` in payload | Event already processed | Second delivery acknowledged without re-processing | HTTP 200; `{"result":"DUPLICATE"}` | Duplicate payment state transition | **Release blocker** |
| Replay / retry behaviour | Re-send the same event after `INVALID_SIGNATURE` | Event in `SIGNATURE_INVALID` state | Remains rejected; not re-processed | HTTP 401 on retry | Processing of previously rejected event | Warning |

---

### 4. Refund Flow

| | |
|---|---|
| **Class** | `RefundControllerV2` (`/api/v2/merchants/{merchantId}/payments/{paymentId}/refunds`) |

| Sub-flow | Trigger | Preconditions | Expected state change | Expected evidence | Failure signal | Severity |
|---|---|---|---|---|---|---|
| Valid full refund | `POST /api/v2/merchants/{mid}/payments/{pid}/refunds` with `amount = capturedAmount` | Payment in `CAPTURED` state; `ADMIN` role | Refund record created; payment moves to `FULLY_REFUNDED`; ledger DR SUBSCRIPTION_LIABILITY / CR PG_CLEARING posted | HTTP 201; `refund.status` present; `refund.completed.total` counter incremented | HTTP 422/500; missing ledger entry | **Release blocker** |
| Partial refund | Same with `amount < capturedAmount` | Payment in `CAPTURED` state | Refund record created; payment moves to `PARTIALLY_REFUNDED` | HTTP 201; cumulative refunded amount correct | HTTP 422; wrong status | Warning |
| Over-refund blocked | `POST` with `amount > refundableAmount` | Any captured payment | Request rejected | HTTP 422; clear error body | HTTP 201 (over-refund allowed) | **Release blocker** |
| Idempotent retry | Repeat `POST` with same `Idempotency-Key` | Refund already created | Cached response returned; no second refund | HTTP 201 (cached); single refund record | Two refunds created | **Release blocker** |

---

### 5. Renewal Flow

| | |
|---|---|
| **Class** | `RenewalScheduler`; `RenewalService`; fires every 5 min (`fixedRate=300_000`, `initialDelay=60_000`) |

| Sub-flow | Trigger | Preconditions | Expected state change | Expected evidence | Failure signal | Severity |
|---|---|---|---|---|---|---|
| Renewal triggered | Wait for scheduler tick (or advance `next_renewal_at` in DB to `now()`) | Subscription in `ACTIVE` with `autoRenewal=true` and `next_renewal_at` elapsed | Renewal invoice created; payment attempted; `next_renewal_at` advanced | Log line `Renewal scheduler: N subscription(s) due`; new payment attempt record | Log `0 subscription(s) due` when there should be some | Warning |
| Successful renewal | Renewal payment succeeds | Subscription in `ACTIVE`; payment method valid | Subscription remains `ACTIVE`; `next_renewal_at` advanced by billing period | `payment_success_total` counter incremented; subscription `endDate` extended | Subscription moves to `PAST_DUE` incorrectly | **Release blocker** |
| Failed renewal → dunning | Renewal payment fails | Subscription `ACTIVE`; payment method set to decline | Subscription moves to `PAST_DUE`; dunning schedule created | Log `Renewal failed for sub {id}`; dunning attempt row with `status=SCHEDULED` | No dunning attempt created | **Release blocker** |

---

### 6. Dunning Flow

| | |
|---|---|
| **Class** | `DunningScheduler`; `DunningService`; fires every 10 min (`fixedRate=600_000`, `initialDelay=90_000`) |

| Sub-flow | Trigger | Preconditions | Expected state change | Expected evidence | Failure signal | Severity |
|---|---|---|---|---|---|---|
| Scheduled attempt processed | Scheduler tick with `SCHEDULED` attempt whose `scheduled_at` has elapsed | Dunning attempt in `SCHEDULED` state | Payment re-attempted; on success subscription reactivated, on failure next attempt scheduled | Log `[dunning-v1] Scheduler tick — processing due attempts` | No log; no state change | **Release blocker** |
| Successful retry | Dunning payment succeeds | Subscription `PAST_DUE`; valid payment method | Subscription returns to `ACTIVE`; `dunning.success.total` incremented | Prometheus `dunning_success_total` counter; subscription `status=ACTIVE` | Subscription remains `PAST_DUE` | **Release blocker** |
| Backup payment method used | Primary method fails; backup method configured | `SubscriptionPaymentPreference` with backup set | Backup method charged | Log entry referencing backup payment method; attempt record shows backup method ID | No backup attempt | Warning |
| Terminal dunning | All attempts exhausted | Dunning at max retries | Subscription suspended/cancelled; `dunning.exhausted.total` incremented | Prometheus `dunning_exhausted_total` counter; subscription `status=SUSPENDED` | Subscription stuck in `PAST_DUE` | Warning |

---

### 7. Scheduler Safety Verification

| | |
|---|---|
| **Classes** | `PrimaryOnlySchedulerGuard`; `SchedulerLockService`; `RenewalScheduler`; `DunningScheduler` |

| Sub-flow | Trigger | Preconditions | Expected state change | Expected evidence | Failure signal | Severity |
|---|---|---|---|---|---|---|
| Non-primary node skip | `scheduler.primary-only.enabled=true`; app connected to read replica or `pg_is_in_recovery()=true` | Multi-node staging environment | Scheduler exits immediately without executing batch | Log `[scheduler-name] not-primary skip` (from `PrimaryOnlySchedulerGuard`) | Batch executes on replica; write-transaction errors | **Release blocker** |
| Advisory lock skip | Two nodes simultaneously try to acquire lock for same scheduler | Two staging app instances, both primary | Second node acquires false; skips batch | Log `[SCHEDULER-LOCK] Lock busy — skipping scheduler=<name>` | Both nodes execute batch (double processing) | **Release blocker** |
| Happy-path execution once | Single primary node; no competing locks | Single-node staging | Batch executes exactly once per tick | Log `[SCHEDULER-LOCK] Acquired batch lock scheduler=<name>`; expected state changes in DB | Multiple executions per tick | **Release blocker** |
| Scheduler health endpoint | `GET /api/v2/admin/schedulers/health` | App running; ADMIN token | All schedulers report `HEALTHY` or `NEVER_RAN` (acceptable on first boot) | HTTP 200; JSON array with per-scheduler `status` field | Any scheduler in `STALE` unexpectedly | Warning |

---

### 8. Startup / Config Validation

| | |
|---|---|
| **Class** | `StartupValidationRunner` (`src/main/java/…/platform/ops/startup/StartupValidationRunner.java`) |
| **Profile** | Use `SPRING_PROFILES_ACTIVE=prod` for staging (as documented in `.env.example`) |

| Sub-flow | Trigger | Preconditions | Expected state change | Expected evidence | Failure signal | Severity |
|---|---|---|---|---|---|---|
| Missing `WEBHOOK_SECRET` | Start app with `SPRING_PROFILES_ACTIVE=prod` and `WEBHOOK_SECRET` unset | None | App refuses to start | Log `[STARTUP] SECURITY: payments.webhook.secret is the insecure dev placeholder`; JVM exits with non-zero code | App starts and serves traffic | **Release blocker** |
| Missing `JWT_SECRET` | Start app with `SPRING_PROFILES_ACTIVE=prod` and `JWT_SECRET` unset | None | App refuses to start | Log `[STARTUP] SECURITY: app.jwt.secret is the insecure dev-only default`; JVM exits | App starts and serves traffic | **Release blocker** |
| Missing `PII_ENC_KEY` | Start app with `SPRING_PROFILES_ACTIVE=prod` and `PII_ENC_KEY` env var not set | None | App refuses to start | Log `[STARTUP] SECURITY: PII_ENC_KEY environment variable is not set`; JVM exits | App starts and serves traffic | **Release blocker** |
| Gateway emulator enabled | Start app with `SPRING_PROFILES_ACTIVE=prod` and `app.gateway-emulator.enabled=true` | None | App refuses to start | Log `[STARTUP] SECURITY: app.gateway-emulator.enabled=true in a non-dev/non-test profile` | App starts with emulator active | **Release blocker** |
| Clean staging boot | Start app with all required env vars set (see `.env.example`) | PostgreSQL available; Flyway migrations applied | App starts successfully | Log `[STARTUP] app=FirstClub Membership Program version=1.0.0 profile=prod`; `/actuator/health` returns `{"status":"UP"}` within 60 s | App fails to start; health remains DOWN | **Release blocker** |

---

### 9. Observability / Health Verification

| | |
|---|---|
| **Endpoints** | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus`, `/ops/health/deep`, `/api/v2/admin/system/health/deep`, `/api/v2/admin/schedulers/health` |

| Sub-flow | Trigger | Preconditions | Expected state change | Expected evidence | Failure signal | Severity |
|---|---|---|---|---|---|---|
| Health endpoint | `GET /actuator/health` | App running | Returns overall status | HTTP 200; `{"status":"UP"}` | HTTP non-200; `status` != `UP` | **Release blocker** |
| Liveness probe | `GET /actuator/health/liveness` | App running | Returns liveness state | HTTP 200; `{"status":"UP"}` | HTTP non-200 | **Release blocker** |
| Readiness probe | `GET /actuator/health/readiness` | App running; DB connected | Returns readiness state | HTTP 200; `{"status":"UP"}` with `db` component UP | HTTP 503; `db` DOWN | **Release blocker** |
| Prometheus metrics | `GET /actuator/prometheus` | App running | Returns metrics in Prometheus text format | HTTP 200; contains `jvm_memory_used_bytes`, `http_server_requests_seconds` | HTTP 404/403 | Warning |
| `requestId` in logs | Execute any API request | App running | JSON log line contains `requestId` field | `jq '. | select(.requestId != null)'` on log output | Log lines missing `requestId` | Warning |
| Deep health | `GET /ops/health/deep` with ADMIN token | App running | Composite health + SLO summary | HTTP 200; JSON with `schedulerHealth`, `projectionLag`, `sloSummary` sections | HTTP 403/500 | Warning |
| System summary | `GET /api/v2/admin/system/health/deep` with ADMIN token | App running | Outbox, DLQ, webhook, dunning backlog counters | HTTP 200; all counter fields present | HTTP 403/500 | Warning |
| No leaked env in `/actuator/info` | `GET /actuator/info` | `management.info.env.enabled=false` in prod profile | Empty or minimal response | HTTP 200; no sensitive env vars in body | Env vars visible in response | **Release blocker** |

---

## Phase 2 — Gap Table

| Gap | Why it matters in staging | Minimal fix | Type | Priority |
|---|---|---|---|---|
| No staging smoke script | No single executable command to validate all critical paths before release | Create `scripts/staging-smoke.sh` | Script | **P0** |
| No staging release checklist | Operators have no structured pass/fail record | Create `docs/operations/staging-release-checklist.md` | Docs | **P0** |
| No `application-staging.properties` | Operators may accidentally use dev profile or miss prod-specific settings | Document in staging plan that `prod` profile + env vars is the correct staging approach; add staging `.env` guidance | Docs | **P1** |
| `PaymentIntentV2ServiceImpl.confirmPaymentIntent` hardcodes `"DECLINED"` as `gatewayStatusCode` (line 279) | Staging test will see `DECLINED` in the failure record regardless of the real gateway code; masked failure detail | Document as known behaviour in staging plan; track as a separate defect | Docs | **P1** |
| No negative-signature webhook test in staging | Signature bypass is a critical security control; it must be confirmed working in the live environment | Include in smoke script with `X-Signature: bad` | Script | **P0** |
| No refund idempotency staging test | Duplicate refunds are a financial integrity risk | Include idempotent-retry step in smoke script | Script | **P0** |
| Scheduler safety not verifiable without DB introspection | Primary-only and advisory-lock skips produce logs but no observable API signal | Document log-based verification; include `GET /api/v2/admin/schedulers/health` in smoke script | Docs + Script | **P1** |
| No explicit staging boot sequence documentation | Operators don't know the correct startup order (DB → migrate → start app → verify) | Include in `staging-release-checklist.md` | Docs | **P1** |
| No `X-Request-Id` / `requestId` verification step | Correlation ID must be present for support triage; missing it silently breaks log tracing | Include in smoke script (check response header) | Script | **P1** |
| Missing seed/setup instructions for staging | Staging DB starts empty; without seed data most flows fail | Include seed verification steps in checklist | Docs | **P1** |

---

## Phase 3 — Implementation Notes

### What is implemented in this PR

1. **`docs/operations/staging-test-plan.md`** (this file) — authoritative staging test matrix, grounded in actual repo endpoints and classes.

2. **`docs/operations/staging-release-checklist.md`** — operator-friendly sequential checklist with pass/fail rows that can be filled out before each release.

3. **`scripts/staging-smoke.sh`** — executable smoke script that validates all nine critical flows using `curl` and `jq`. It:
   - Is safe by default (no embedded secrets; all credentials are env-driven)
   - Prints `PASS` / `FAIL` per step with a final exit code summary
   - Fails with clear output on any critical assertion failure
   - Can be run in CI (set `BASE_URL`, `ADMIN_USER`, `ADMIN_PASS`, `WEBHOOK_SECRET` as env vars)

### What is NOT in scope for this PR

- Application redesign or refactoring
- New infrastructure (Kubernetes manifests, Terraform, Helm)
- Changes to business logic (the `confirmPaymentIntent` hardcoded code is documented as a known issue to track separately)
- Full end-to-end framework replacement
- New production monitoring infrastructure

---

## Phase 4 — Validation

### How to validate this plan itself

1. **Script syntax validation:**
   ```bash
   bash -n scripts/staging-smoke.sh
   ```

2. **Run existing unit tests (unchanged by this PR):**
   ```bash
   mvn test --no-transfer-progress
   ```

3. **Dry-run smoke script against a local dev instance:**
   ```bash
   # Start local stack
   ./scripts/dev-up.sh
   mvn spring-boot:run -Dspring-boot.run.profiles=local &

   # Run smoke (dry-run mode skips actual assertions but prints all steps)
   BASE_URL=http://localhost:8080 \
   ADMIN_USER=admin \
   ADMIN_PASS=admin123 \
   WEBHOOK_SECRET=dev-only-webhook-secret-change-in-prod \
   ./scripts/staging-smoke.sh
   ```

---

## Operator Instructions — How to Verify Staging

### Prerequisites

```bash
# Required tools
curl --version && jq --version && openssl version

# Required environment variables (no defaults for secrets)
export BASE_URL="https://staging.firstclub.example.com"
export ADMIN_USER="admin"
export ADMIN_PASS="<your-staging-admin-password>"
export WEBHOOK_SECRET="<your-staging-WEBHOOK_SECRET>"
export JWT_SECRET="<your-staging-JWT_SECRET>"
```

### Step-by-step

1. **Verify DB and Flyway migrations ran cleanly:**
   Check the app startup logs for `[STARTUP] Chart of accounts present (N accounts)`.

2. **Run the staging smoke script:**
   ```bash
   ./scripts/staging-smoke.sh
   ```
   All steps should exit `PASS`. Any `FAIL` is a release blocker.

3. **Fill out the staging release checklist:**
   See `docs/operations/staging-release-checklist.md`. Sign off each section.

4. **Check observability:**
   ```bash
   # Health
   curl -s "$BASE_URL/actuator/health" | jq .

   # Readiness
   curl -s "$BASE_URL/actuator/health/readiness" | jq .

   # Deep health (requires admin token from smoke run)
   curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/ops/health/deep" | jq .

   # Scheduler health
   curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/api/v2/admin/schedulers/health" | jq .
   ```

5. **Check Prometheus metrics:**
   ```bash
   curl -s "$BASE_URL/actuator/prometheus" | grep -E "jvm_memory|http_server_requests|payment_success"
   ```

6. **Review startup logs for any `[STARTUP]` WARN or ERROR lines.**

---

## Release Recommendation Criteria

| Result | Recommendation |
|---|---|
| All `staging-smoke.sh` steps PASS and checklist fully signed | **SAFE TO MERGE** |
| One or more smoke steps FAIL | **NOT SAFE TO MERGE** — fix before release |
| Checklist has unsigned release-blocker rows | **NOT SAFE TO MERGE** |
| Only Warning-severity gaps remain, documented and accepted by release owner | **SAFE TO MERGE** with documented exceptions |

---

## Known Issues / Accepted Deviations

| Issue | Severity | Status |
|---|---|---|
| `PaymentIntentV2ServiceImpl.confirmPaymentIntent` line 279 hardcodes `"DECLINED"` as `gatewayStatusCode` on failure, regardless of actual gateway response code | Warning | Known — track as separate defect; does not block staging validation |

---

## What This Enables Before Step 7

- **End-to-end flow coverage**: Every critical business path (auth, payment, webhook, refund, renewal, dunning) has an explicit pass/fail criterion before the final release-readiness assessment.
- **Repeatable evidence**: The smoke script produces a structured pass/fail output that can be attached to the release record.
- **Security surface confirmed**: The staging plan explicitly verifies that JWT rejection, webhook signature enforcement, fail-fast config guards, and the no-env-leak actuator rule all hold in the live environment.
- **Scheduler safety observable**: The `GET /api/v2/admin/schedulers/health` verification step and log-based advisory-lock skip confirmation give operators confidence that no double-processing will occur in production.
- **Observability chain validated**: Health probes, Prometheus scrape, deep health, and structured log correlation fields are all verified in staging before production traffic is directed at the service.
- **Operator onboarding**: The release checklist gives any engineer on call a clear sequence to follow; no institutional knowledge required.
- **CI integration ready**: `staging-smoke.sh` uses only env vars for credentials and exits non-zero on failure, making it directly usable in a CI gate.
- **Gap documentation**: The gap table captures the `confirmPaymentIntent` hardcoded failure code as a known issue, preventing it from being re-discovered as a surprise during Step 7.
- **Profile discipline**: The plan confirms that `SPRING_PROFILES_ACTIVE=prod` with real env vars is the correct staging setup, preventing accidental dev-profile deployments.
- **Release recommendation framework**: Clear SAFE / NOT SAFE criteria are defined, giving the Step 7 release-readiness assessment a concrete input to act on.
