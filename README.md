# FirstClub Membership Platform

A modular-monolith billing, subscriptions, and payments platform built to
demonstrate production-grade backend engineering. The system manages the full
financial lifecycle — from a customer enrolling in a plan through invoice
generation, payment orchestration, ledger posting, revenue recognition, and
reconciliation — with an explicit focus on financial correctness, concurrency
safety, and operational explainability.

Built by **Shwet Raj** as a personal engineering depth project, documented and
hardened across 24 iterative phases.

---

![Java](https://img.shields.io/badge/Java-17%2B-orange?style=flat-square)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.3-brightgreen?style=flat-square)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?style=flat-square)
![Flyway](https://img.shields.io/badge/Flyway-69%20migrations-red?style=flat-square)
![Tests](https://img.shields.io/badge/Tests-2071%20passing-success?style=flat-square)
![Redis](https://img.shields.io/badge/Redis-opt--in%20layer-DC382D?style=flat-square)

---

## Why This Project Is Worth Reviewing

This is not a subscription CRUD app. The domain surface covers:

- **Multi-tenant architecture** — every entity is scoped to a `merchant_id`; customers, plans, payments, ledger, and webhooks are fully tenant-isolated
- **Double-entry ledger** — immutable journal with a typed Chart of Accounts; balance invariant enforced at the service layer before any write
- **3-layer idempotency** — PostgreSQL composite key (Layer 1) + Redis response cache (Layer 2) + Redis NX in-flight lock (Layer 3); degrades gracefully when Redis is unavailable
- **Transactional outbox with leasing** — domain events are written in the same DB transaction as the business change; a lease + heartbeat model prevents ghost processing on node failure
- **Revenue recognition** — daily amortization schedule per subscription aligned with ASC 606 / IFRS 15 earn-as-you-go principles, with earned/deferred split tracking
- **Reconciliation engine** — expected-vs-actual matching across gateway callbacks, with mismatch classification, auto-resolution rules, and manual repair flows
- **Dunning engine** — configurable retry policy, failure classification, and escalation logic
- **Risk scoring** — rule-based evaluator with score decay, review queues, and case management
- **Concurrency model documented per domain** — each bounded context specifies its locking strategy (optimistic, pessimistic, unique constraint, Redis NX) with explicit accepted races and failure modes
- **69 Flyway migrations** — schema evolution is fully versioned, traceable, and documented
- **80+ architecture and operations documents** — every significant design decision is written down

---

## System Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                    FirstClub Modular Monolith                       │
│                                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────────────┐ │
│  │ merchant │  │ customer │  │ catalog  │  │   subscription     │ │
│  │ (tenant) │  │ identity │  │ pricing  │  │   lifecycle        │ │
│  └──────────┘  └──────────┘  └──────────┘  └────────────────────┘ │
│                                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────────────┐ │
│  │ billing  │  │ payments │  │  ledger  │  │    revenue         │ │
│  │ invoices │  │ intents  │  │ double-  │  │    recognition     │ │
│  │ tax      │  │ attempts │  │ entry    │  │    (ASC 606)       │ │
│  └──────────┘  └──────────┘  └──────────┘  └────────────────────┘ │
│                                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────────────┐ │
│  │ refunds  │  │ disputes │  │ dunning  │  │ reconciliation     │ │
│  │ reversals│  │ chargebck│  │ retry    │  │ mismatch classify  │ │
│  └──────────┘  └──────────┘  └──────────┘  └────────────────────┘ │
│                                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────────────┐ │
│  │  outbox  │  │  events  │  │ webhooks │  │   risk engine      │ │
│  │  leasing │  │  replay  │  │ delivery │  │   scoring/review   │ │
│  └──────────┘  └──────────┘  └──────────┘  └────────────────────┘ │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │ platform — ops, scheduler, locking, rate-limit, projections  │  │
│  │  admin search · integrity checks · support timeline · audit  │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  PostgreSQL 16 (source of truth)        Redis 7 (acceleration)     │
└─────────────────────────────────────────────────────────────────────┘
```

**Single deployable JAR.** Modules share a database but communicate through
service interfaces and domain events — not direct cross-module JPA joins. The
outbox bridges async boundaries without introducing a broker dependency.

---

## Domain Modules

| Module | Responsibility |
|---|---|
| `merchant` | Tenant root. Every resource is scoped to `merchant_id`. API key auth, merchant status state machine. |
| `customer` | Customer identity, PII encryption (AES-256-GCM), customer notes. |
| `catalog` | Products, prices, pricing models, versioned price history. |
| `subscription` | Subscription lifecycle, billing cycles, state machine (`@Version` optimistic locking), schedule management. |
| `billing` | Invoice generation, line items (charge / discount / proration / tax), credit notes, invoice rebuild guard. |
| `billing.tax` | Tax profile resolution, inclusive/exclusive tax calculation, line-item tax posting. |
| `payments` | Payment intents (V2), payment attempts, gateway routing, method/mandate management. |
| `payments.refund` | Partial and full refunds with over-refund guard, idempotency fingerprint, merchant scoping. |
| `payments.disputes` | Chargeback lifecycle, evidence management, dispute reserve accounting. |
| `ledger` | Immutable double-entry journal. Balance invariant enforced before write. Reversal-based corrections only. |
| `ledger.revenue` | Revenue recognition schedules, daily amortization, earned/deferred split, audit trail. |
| `outbox` | Transactional outbox with lease + heartbeat model, per-aggregate ordering, DLQ, stale lease recovery. |
| `events` | Append-only domain event log, schema versioning, replay infrastructure. |
| `dunning` | Retry policy engine, failure classification, escalation, dunning schedule. |
| `recon` | Gateway reconciliation, expected-vs-actual matching, mismatch classification, FX field support. |
| `risk` | Rule-based risk scoring, score decay, review queue, case management. |
| `notifications.webhooks` | Webhook endpoint management, at-least-once delivery with retry and DLQ. |
| `reporting.projections` | Denormalized read models maintained by event listeners, projection rebuild scheduler. |
| `integrity` | Background checker that validates ledger balance, recon consistency, and refund invariants. |
| `platform` | Distributed locking (fencing token), scheduler execution tracking, rate limiting, Redis abstraction, state machine validator, ops repair actions, SLO tracking, API versioning. |
| `admin` | Cross-merchant search with Redis-backed result caching. |
| `support` | Support case management, customer support timeline. |
| `audit` | Financial audit trail, `@FinancialOperation` annotation, immutable audit log for money-moving operations. |

---

## How Money Flows

```
Customer subscribes to plan
        │
        ▼
Subscription created (state: ACTIVE)
  └─ @Version optimistic lock prevents duplicate active subscription
        │
        ▼
Billing engine generates Invoice
  ├─ header: merchant, customer, billing period
  ├─ lines: base charge + proration + tax
  └─ invoice number from atomic sequence (SELECT FOR UPDATE on invoice_sequences)
        │
        ▼
PaymentIntent created against invoice
  └─ idempotency key prevents double-intent on retry
        │
        ▼
Payment gateway called → PaymentAttempt recorded
        │
   ┌────┴────┐
SUCCESS    FAILURE
   │           └─ dunning engine schedules retry
   ▼
PaymentIntent confirmed (SELECT FOR UPDATE on intent row)
  ├─ invoice → PAID
  ├─ LedgerEntry posted: DR RECEIVABLE / CR SUBSCRIPTION_LIABILITY
  ├─ RevenueRecognitionSchedule generated (daily amortization)
  ├─ DomainEvent written to outbox (same transaction)
  └─ Subscription status confirmed
        │
        ▼
Outbox poller delivers event → webhook → merchant system
        │
        ▼  (nightly)
Reconciliation: expected settlement vs gateway report
  └─ mismatches → classified → auto-resolved or queued for ops review
```

**Refund path:** Pessimistic lock on payment row → over-refund guard enforced
→ `refunded_amount` incremented → ledger reversal posted (`DR REFUND_EXPENSE /
CR CASH`) → outbox event fired. Dispute path is structurally similar with a
dedicated `DISPUTE_RESERVE` account and separate lifecycle state machine.

---

## Consistency and Correctness Model

**PostgreSQL is the only source of financial truth.** Redis is an acceleration
layer. Financial correctness holds with Redis completely unavailable.

| Concern | Mechanism |
|---|---|
| No floating-point money | All amounts stored as `NUMERIC(19,4)` |
| Ledger immutability | No UPDATE or DELETE ever runs on `ledger_entries/lines`; enforced by row-security policy (V56) and application-layer guard |
| Balance invariant | `LedgerServiceImpl.postEntry()` validates `sum(DEBIT) == sum(CREDIT)` before any persistence |
| Correction model | All adjustments via reversal entries — no overwriting of existing journal entries |
| Invoice number uniqueness | `SELECT FOR UPDATE` on per-merchant `invoice_sequences` row; no gaps, no duplicates under concurrency |
| Subscription deduplication | Unique constraint on `(merchant_id, customer_id, plan_id, status=ACTIVE)` + `@Version` optimistic locking |
| Payment double-charge prevention | Idempotency key on intent creation + unique active intent per invoice check |
| Refund over-refund prevention | `SELECT FOR UPDATE` on payment row + SHA-256 request fingerprint for dedup |
| Outbox exactness | Events written in same DB transaction as business change; lease + heartbeat prevents ghost processing on JVM crash |
| Replay safety | Domain events are append-only; all handlers are idempotent |

---

## Concurrency and Idempotency

The platform uses layered defences calibrated per domain. The full model is
documented in [`docs/architecture/06-concurrency-model.md`](docs/architecture/06-concurrency-model.md).

**Optimistic locking** — subscription state transitions use `@Version`; a
concurrent state change raises `OptimisticLockException`, and the client is
instructed to re-read and retry. Chosen because conflicts are rare and fail-fast
is preferable to queuing.

**Pessimistic locking** — payment confirmation and refund issuance use
`SELECT FOR UPDATE` on the payment row. The window is known and short; holding
the lock is cheaper than resolving a double-refund post-hoc.

**FOR UPDATE SKIP LOCKED** — outbox poller, dunning scheduler, and
reconciliation batch workers use skip-locked to distribute work across concurrent
JVM threads without row contention.

**3-layer idempotency:**

```
Incoming request with Idempotency-Key header
        │
        ├─ Layer 3 (Redis NX lock): concurrent duplicate? → 409 immediately
        │
        ├─ Layer 2 (Redis cache): key seen recently? → return cached response
        │
        └─ Layer 1 (PostgreSQL composite key): key in idempotency_keys? → return stored response
                │ (first-time request)
                └─ execute business logic → store response → return
```

Layers 2 and 3 degrade gracefully to Layer 1 when Redis is unavailable.

**Distributed locking** — `platform.scheduler.lock` implements fencing-token
based distributed locks (V52 migration) for cross-node scheduler coordination
when running multiple JVM instances.

---

## Ledger, Reconciliation, and Revenue Recognition

### Double-Entry Ledger

Every financial event produces a balanced journal entry across typed accounts:

| Account | Type | Used for |
|---|---|---|
| `RECEIVABLE` | ASSET | Invoiced amounts not yet collected |
| `CASH` | ASSET | Settled payments received |
| `DISPUTE_RESERVE` | ASSET | Funds held during chargeback |
| `REVENUE_SUBSCRIPTIONS` | INCOME | Earned subscription revenue |
| `SUBSCRIPTION_LIABILITY` | LIABILITY | Collected but unearned (deferred) revenue |
| `REFUND_EXPENSE` | EXPENSE | Cost of issued refunds |
| `CHARGEBACK_EXPENSE` | EXPENSE | Cost of lost chargebacks |

### Revenue Recognition

Subscription payments are initially posted to `SUBSCRIPTION_LIABILITY` (deferred
revenue). A `RevenueRecognitionSchedule` distributes daily amortization entries
(`DR SUBSCRIPTION_LIABILITY / CR REVENUE_SUBSCRIPTIONS`) across the subscription
period, following ASC 606 / IFRS 15 earn-as-you-go principles. Each schedule
line is processed exactly once (a guard column prevents re-recognition even on
retry).

### Reconciliation

Nightly reconciliation compares expected settlement amounts — derived from the
platform's payment records — against gateway-reported amounts. Mismatches are
classified (timing gap, amount mismatch, gateway-only record, platform-only
record), auto-resolved where rules permit, and queued for operator review
otherwise. See [`docs/operations/01-reconciliation-playbook.md`](docs/operations/01-reconciliation-playbook.md)
for the full operational model.

---

## Redis and the Scaling Path

Redis is infrastructure-ready but **disabled by default** (`app.redis.enabled=false`).
Enabling it unlocks:

| Feature | Effect |
|---|---|
| Idempotency fast-path | Replaces DB SELECT per duplicate request with sub-ms Redis GET |
| In-flight dedup lock | `SET NX EX` prevents two concurrent identical requests both proceeding to DB |
| Rate limiting | Token-bucket per `(merchantId, endpoint)` enforced entirely in Redis |
| Merchant settings cache | Eliminates repeated config SELECTs on every hot-path request |
| Projection caches | Reduces reporting query load on denormalized read models |

**Current realistic throughput:** Several hundred subscription creates per
minute; low thousands of payment callbacks per minute — bounded by PostgreSQL
write IOPS and JVM thread pool. This is appropriate for a SaaS platform at
early-to-mid growth scale.

**Scaling path** — documented honestly in [`docs/architecture/08-scaling-path.md`](docs/architecture/08-scaling-path.md):

1. Enable Redis → reduce DB pressure on idempotency and rate-limiting hot paths
2. Add PostgreSQL read replica → route reconciliation and reporting reads
3. Partition high-growth tables (`ledger_lines`, `revenue_recognition_schedules`, `domain_events`) — migration files already model the partition key choices
4. Introduce a message broker to replace the outbox poller with proper consumer groups — the outbox schema is already broker-compatible
5. Extract per-domain services when a specific domain's write volume justifies it

The architecture is designed to evolve incrementally. None of these steps require
rewriting the domain logic.

---

## Running Locally

### Prerequisites

- Java 17+
- Maven 3.9+
- Docker + Docker Compose (for the PostgreSQL path)

### PostgreSQL setup — recommended for serious evaluation

```bash
# 1. Start PostgreSQL + pgAdmin
docker-compose up -d

# 2. Run the app with the local profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Flyway runs all 69 migrations automatically on first startup.

| URL | Purpose |
|---|---|
| `http://localhost:8080/swagger-ui.html` | Interactive API documentation (springdoc) |
| `http://localhost:8080/actuator/health` | Health endpoint with component detail |
| `http://localhost:8080/actuator/prometheus` | Prometheus metrics scrape endpoint |
| `http://localhost:5050` | pgAdmin UI (`admin@firstclub.com` / `admin`) |

### H2 in-memory profile — quick smoke test only

```bash
./mvnw spring-boot:run
# Default profile (dev) — uses H2 in-memory. Fast to start, not for schema evaluation.
```

> The `dev` profile uses H2 for zero-friction startup. For any meaningful review
> of the financial schema, migrations, constraints, or locking behaviour, use the
> PostgreSQL path above.

### Enable Redis (optional)

```bash
docker run -p 6379:6379 redis:7-alpine

# Add to your run command:
-Dapp.redis.enabled=true
```

---

## Running Tests

```bash
# Full unit test suite — no Docker required
./mvnw test

# Integration tests — require Docker (Testcontainers PostgreSQL)
./mvnw test -Dtest="*IT"

# Specific concurrency test
./mvnw test -Dtest="RefundConcurrencyIT"

# Mutation testing (PITest — 85% mutation coverage threshold)
./mvnw org.pitest:pitest-maven:mutationCoverage
```

**Test organisation:**

| Type | Count | What they cover |
|---|---|---|
| Unit tests | ~2000 | Service and domain logic, Mockito-based, no Spring context |
| Integration tests | ~70 | Full Spring context + Testcontainers PostgreSQL |
| Concurrency tests | Several | `CountDownLatch` + `ExecutorService` proving locking invariants hold under load |
| Chaos tests | Several | Lease expiry, stale recovery, gateway unknown-outcome scenarios |

---

## Review This Repo in 5 Minutes

If you are a senior engineer or interviewer and want a fast signal read:

| Step | Where to look | What you will see |
|---|---|---|
| 1 | [`docs/architecture/00-engineering-principles.md`](docs/architecture/00-engineering-principles.md) | The 10 principles that govern every design decision |
| 2 | [`docs/architecture/02-bounded-contexts.md`](docs/architecture/02-bounded-contexts.md) | How modules are divided and what crosses their boundaries |
| 3 | [`docs/architecture/06-concurrency-model.md`](docs/architecture/06-concurrency-model.md) | Per-domain locking strategy with explicit accepted races |
| 4 | [`docs/accounting/01-ledger-model.md`](docs/accounting/01-ledger-model.md) | Double-entry design, Chart of Accounts, balance invariant |
| 5 | [`docs/api/01-idempotency-model.md`](docs/api/01-idempotency-model.md) | 3-layer model with failure modes per layer |
| 6 | [`docs/architecture/10-outbox-pattern.md`](docs/architecture/10-outbox-pattern.md) | Lease + heartbeat, stale lease recovery, per-aggregate ordering |
| 7 | [`docs/architecture/08-scaling-path.md`](docs/architecture/08-scaling-path.md) | Honest bottleneck analysis and incremental evolution steps |
| 8 | [`docs/operations/01-reconciliation-playbook.md`](docs/operations/01-reconciliation-playbook.md) | Operational reality of running a financial system |
| 9 | [`src/test/java/com/firstclub/concurrency/`](src/test/java/com/firstclub/concurrency/) | Executable proof of locking invariants under load |
| 10 | [`src/main/resources/db/migration/`](src/main/resources/db/migration/) | 69 versioned migrations tracing system evolution |

---

## Documentation Index

```
docs/
├── architecture/     Core design: bounded contexts, write paths, concurrency model,
│                     outbox, distributed locking, projections, scaling, DB hardening
├── accounting/       Ledger model, revenue recognition, refunds/disputes accounting,
│                     reconciliation layers
├── api/              Idempotency model, webhook contracts, error model,
│                     API versioning, auth and API key model
├── operations/       Reconciliation playbook, DLQ runbook, risk review flow,
│                     incident response, manual repair, Redis failure modes,
│                     scheduler runbook, integrity checks, observability/SLOs,
│                     financial audit trail, staging test plan,
│                     staging release checklist
├── performance/      Bottleneck analysis, Redis usage, hot paths, load test notes
├── testing/          Concurrency test strategy, chaos test strategy,
│                     mutation test strategy
└── *.md              Domain deep-dives: billing, payments, dunning, outbox,
                      subscriptions, risk, ledger, recon, gateway routing,
                      idempotency, projections, webhooks, tenant model, tax engine
```

---

## Project Structure

```
src/main/java/com/firstclub/
├── merchant/           Tenant root, API keys, merchant status state machine
├── customer/           Customer identity, PII encryption
├── catalog/            Products, prices, pricing models
├── subscription/       Subscription lifecycle, billing schedule
├── billing/            Invoices, tax, credit notes, proration
├── payments/           Intents, attempts, gateway routing, refunds, disputes
├── ledger/             Double-entry journal, revenue recognition, reversals
├── outbox/             Transactional outbox, leasing, DLQ, ordering
├── events/             Domain event log, schema versioning, replay
├── dunning/            Retry policy, failure classification, escalation
├── recon/              Reconciliation engine, mismatch classification
├── risk/               Scoring, review queue, case management
├── notifications/      Webhook delivery, endpoint management
├── reporting/          Projections, denormalized read models, ops timeline
├── integrity/          Background invariant checkers
├── platform/           Distributed locking, scheduler, rate limiting, Redis,
│                       state machine, ops repair, SLOs, API versioning
├── admin/              Cross-merchant search
├── support/            Support cases, customer timeline
└── audit/              Financial audit trail, @FinancialOperation aspect

src/main/resources/db/migration/
└── V1 → V69           Full Flyway migration history (69 versions)

docs/
└── architecture/ accounting/ api/ operations/ performance/ testing/ *.md
```

---

## Engineering Proof Points

**Financial correctness is explicit, not assumed.**
Money is `NUMERIC(19,4)`. The ledger is append-only — corrections are reversal
entries, not overwrites. The balance invariant is enforced in code before the DB
write, not delegated to callers.

**Idempotency is a first-class primitive.**
Every state-mutating API accepts an idempotency key. The 3-layer model means a
client can retry indefinitely and will always receive the same response without
triggering duplicate processing.

**Concurrency is specified per domain.**
[`docs/architecture/06-concurrency-model.md`](docs/architecture/06-concurrency-model.md)
names the exact lock type used in each domain, the reason it was chosen over
alternatives, and the accepted failure mode on conflict. This is not "we use
transactions" — it is lock-selection with documented tradeoffs.

**The outbox is hardened past the obvious pattern.**
Beyond "write event to same transaction," the implementation includes lease
acquisition, heartbeat renewal, stale lease recovery, per-aggregate ordering,
DLQ routing, and `FOR UPDATE SKIP LOCKED` polling. It survives JVM crashes
without ghost-processing events.

**Operational tooling is present.**
Manual repair actions, reconciliation runbooks, DLQ retry procedures, integrity
check playbooks, and support timeline endpoints exist. The system is built to be
operated, not just started.

**Observability is wired in.**
Prometheus metrics via Micrometer, structured JSON logs (Logstash encoder),
`/actuator/health` with component detail, SLO tracking, and a `@FinancialOperation`
audit aspect that decorates all money-moving calls.

---

## Current Status

This is a **personal engineering depth project**, not deployed production SaaS.
It is built to demonstrate backend breadth and depth across financial systems
design, concurrency, operational maturity, and technical documentation.

**Completed across 24 phases:**
- Full billing, payments, ledger, and revenue recognition pipeline
- Concurrency hardening per domain (locking strategies, idempotency, outbox)
- Redis infrastructure (complete; enabled as opt-in via `app.redis.enabled=true`)
- Reconciliation, dunning, risk, and dispute engines
- Observability, SLOs, ops tooling, and financial audit trail
- Integration and concurrency test suites (2071 passing)
- Mutation testing configuration (PITest, 85% mutation threshold)
- 80+ architecture and operations documentation files

**Honest gaps:**
- No message broker — the outbox poller handles async delivery in-process; a Kafka migration path is documented but not implemented
- No horizontal scale tested — distributed lock infrastructure is in place (V52) but not exercised under multi-node load
- Redis fast-paths are infrastructure-ready but disabled by default; not run in any persistent test environment
- No measured load test results — throughput estimates in the scaling docs are architectural projections, not benchmarks

---

## Author

**Shwet Raj**
[github.com/ishwetraj17](https://github.com/ishwetraj17)

Built as a long-running engineering project to go beyond tutorial-grade backend
work into the territory of financial systems design, correctness engineering, and
operational platform thinking.
