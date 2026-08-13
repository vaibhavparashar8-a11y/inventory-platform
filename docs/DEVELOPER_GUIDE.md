# Developer Guide

The handover document for this project. Assumes you arrive with zero context and
need to ship a feature. If anything here disagrees with the code, the code is
right and this file is a bug — fix it.

**Current state: Phase 0 (foundation), in progress.** Nothing here is a product
yet. See §2 for what actually exists.

---

## 1. What this product is

A local-first inventory, sales and profitability system for a small retailer.

The first customer is a single garment seller running on one Windows PC. The same
codebase is sold to other sellers and extended to other product verticals, so
every decision is judged by: *does this still hold at 50 customers and a second
vertical?*

- **Purchases** are entered by form or Excel upload and raise stock.
- **Sales** happen through configurable *firms* — two Flipkart seller accounts and
  an offline counter — all drawing from **one shared stock pool**.
- **Returns** are recorded against a sale line and reduce or restore stock.
- A **dashboard** reports sales, returns, write-offs and profit & loss.

**Business model:** single-PC install today, multi-customer product later. The
customer is non-technical with no IT support, which is why "the user will
reconcile it manually" is never an acceptable design here.

---

## 2. Feature catalogue

| Feature | State | Phase |
|---|---|---|
| Multi-module build, pinned versions, Spotless + Enforcer gates | **built** | 0 |
| Money value types (`Money` scale 2, `UnitCost` scale 4, `Quantity`) | **built** | 0 |
| RFC 9457 error model with stable problem type URIs | **built** | 0 |
| Tenant context (single place the tenant is resolved) | **built** | 0 |
| Idempotency (`IdempotentOperation` + store SPI) | **built** | 0 |
| `ServiceClient` SPI — in-process and HTTP bindings | **built** | 0 |
| Credential encryption (AES-GCM, key rotation, redaction) | **built** | 0 |
| Shared ArchUnit rules, proven to fire | **built** | 0 |
| OpenAPI contracts for all eight services (skeletons) | **built** | 0 |
| CI: build, test, gates, Spectral lint, CodeQL, Dependabot | **built** | 0 |
| catalog-service / stock-service skeletons, Flyway, health split | **built** | 0 |
| Gateway | **in progress** | 0 |
| Composite single-JVM launcher | **in progress** | 0 |
| End-to-end trace id across services | **in progress** | 0 |
| PostgreSQL migration proof in CI | **planned** | 0 |
| Catalog CRUD, pack manifests | planned | 1 |
| Stock ledger, balances, reservations | planned | 1 |
| Channel scaffolding, OAuth frame, stub connector | planned | 1 |
| Purchases, weighted average costing | planned | 2 |
| Sales, offline counter, returns | planned | 3 |
| File import with preview | planned | 4 |
| Reporting and dashboard | planned | 5 |
| Flipkart API connector | planned | 6 |
| Windows installer, backup/restore, upgrade path | planned | 7 |
| Toys pack (manifest only) | planned | 8 |
| Cloud and mobile | design only | 9 |
| Tax / GST, licensing, authentication, multi-currency | **deferred** | — |

---

## 3. Service map

| Service | Owns | Must never own |
|---|---|---|
| `gateway` | React bundle, routing `/api/v1/**`, the only port the browser sees | business logic of any kind |
| `catalog-service` | items, variants, categories, firms, pack manifests | quantity |
| `stock-service` | **the stock ledger, balances, reservations** | prices, costs, orders |
| `purchase-service` | supplier invoices, weighted average cost | quantity (calls stock) |
| `sales-service` | orders, counter, batch entry, `costAtSale` | quantity, cost calculation |
| `returns-service` | both return axes, write-offs | quantity |
| `channel-service` | import/fetch pipeline, credentials, OAuth | sales records |
| `reporting-service` | read model, projections | any write to business data |

**Call graph (Phase 0):** browser → gateway → (in-process) catalog, stock.

Later: purchase/sales/returns → stock for every quantity change; sales → purchase
for cost at sale; all → reporting via the outbox.

---

## 4. Architectural decisions

Full records in [`docs/adr/`](adr/). Summary of what was decided and what was
rejected:

| Decision | Rejected alternative | Why |
|---|---|---|
| Microservices, database per service | Modular monolith | Cloud phase and multi-customer sale need real boundaries; the launcher gives us monolith-like deployment anyway |
| Single-writer stock ledger | Sagas / compensating transactions | The no-negative invariant must hold at every instant; one local transaction enforces it, and there is no provisional state to undo |
| Two-phase reservations | Distributed transactions | Self-healing via TTL, no manual reconciliation screen |
| H2 file mode locally | SQLite | SQLite has no row-level locking or `SELECT … FOR UPDATE`; the invariant depends on both. ADR 0002 |
| Java 21 + Spring Boot 4.1 | Java 17 + Boot 3.5 | Current LTS and current framework line for a multi-year product. ADR 0001 |
| JSON attributes + 2 promoted columns | Full EAV | EAV makes the dashboard slow |
| One-way cloud push (Phase 9) | Bidirectional sync | Conflict resolution on stock quantities fails as wrong stock counts |
| `jlink`/`jpackage` installer | Docker on the desktop | A shopkeeper cannot install Docker |
| No authentication in v1 | Auth now | Single-user, localhost-bound. Seam left. ADR 0005 |

---

## 5. The single-writer stock rule

**`stock-service` is the sole writer of stock quantity.** No other service has a
stock table, a quantity column, or any way to change on-hand.

"Stock never goes negative" is an invariant that must hold at **every instant**,
not eventually. Keeping the ledger in one service means one local database
transaction enforces it: two concurrent sales both call `stock-service`, it
serialises them, and rejects one *before anything is committed anywhere*. There is
no provisional state, so there are no compensating transactions.

### Locking protocol

- One `stock_balance` row per `(tenant_id, variant_id)`, unique-constrained
- `available = on_hand - reserved`; outbound checks `available`, never `on_hand`
- Every quantity-changing transaction takes a **pessimistic write lock** on the
  balance rows it touches, in one transaction. `@Version` is a second line of
  defence, not the mechanism
- Multi-line operations lock rows in **ascending `variant_id` order**, so deadlock
  is structurally impossible
- Check and write happen in the same transaction — never read, return, then write

### Reservation protocol

1. `POST /reservations` — hold quantity, returns id + TTL (default 120s)
2. Caller commits its own business record in its own local transaction
3. `POST /reservations/{id}/commit` — the ledger row is written
4. On failure `/release`; if the caller dies, the TTL expires and quantity returns

- Expiry is evaluated **at read time as well as by the sweeper** — an expired but
  unswept reservation must never block a sale
- Committing an expired reservation → 409 `urn:problem:reservation-expired`
- `commit`/`release` are idempotent
- **Reservations are outbound-only.** Increases cannot violate the invariant, so
  they post a single idempotent movement call

---

## 6. Domain model and invariants

Phase 1 onward. Recorded here now because the invariants are decided:

- **Ledger is the source of truth**, append-only. No update, no delete on
  `stock_movement`; corrections are `ADJUSTMENT` rows with a reason code
- **Cost frozen at sale.** `costAtSale` is captured at the moment of sale so
  historical profit never changes when purchase costs move. If it cannot be
  resolved, store `null` and flag `costMissing` — **never zero**, which would
  report 100% margin as fact
- **Two independent return axes**: type (`COURIER_RETURN`/`CUSTOMER_RETURN`) and
  disposition (`RESTOCK`/`DAMAGED`/`LOST`). Two enums, never collapsed
- **Natural keys make imports idempotent.** Sale line:
  `(firmId, channelId, externalOrderId, externalShipmentRef, externalLineId)`
- **Money scales**: prices/fees/totals at 2 decimals, unit costs and weighted
  averages at 4. Never round intermediates
- **Time**: store UTC always; date filters use the tenant's configured business day

---

## 7. The channel pipeline

Phase 1/4/6. File import and API fetch converge on one normalisation pipeline;
both must produce identical results, and neither posts to `sales-service` without
a preview showing matched and unmatched SKUs. Adding a connector means
implementing `ChannelConnector` — nothing in the core changes.

---

## 8. How to add a vertical pack

Phase 1/8. A pack is a manifest, not code. Worked example lands with
`pack-apparel`.

---

## 9. How to add a new service

1. OpenAPI spec in `contracts/` **first**; lint passes
2. Module under `services/`, parent = root POM, depends on `platform-common`
3. `V1__baseline.sql` — portable SQL, `tenant_id` on every table
4. `application.yml` — H2 file mode, Flyway, `ddl-auto: validate`
5. **`spring-boot-flyway` dependency** — see §12, without it migrations silently
   never run
6. `HealthOperations` with liveness and readiness as distinct checks
7. Register in-process operations for the launcher
8. `ArchitectureTest` using the shared rules
9. Startup test asserting Flyway actually migrated
10. Add the module to the root POM and to the launcher

---

## 10. Local setup

Prerequisites: **JDK 21**, **Node 24**. Maven comes from the committed wrapper.

```bash
./mvnw verify                          # build, test, ArchUnit, formatting gate
./mvnw spotless:apply                  # fix formatting
./mvnw -pl services/stock-service test # one module
npx @stoplight/spectral-cli lint "contracts/*.yaml" --ruleset .spectral.yaml
```

Databases are H2 files under `./data/`. **Resetting** is deleting that folder;
Flyway rebuilds on next start. Never commit it — it is git-ignored and the
pre-commit hook blocks it.

Docker is not installed on the current dev machine, so Testcontainers/PostgreSQL
runs **in CI only**. Local tests use H2.

---

## 11. Build and release

Per phase: PR → merge → annotated tag (`v0.1.0-foundation`, …) → `CHANGELOG.md`.
Installer and the customer upgrade/rollback procedure land in Phase 7.

---

## 12. Spring Boot 4 notes

Findings that cost time, recorded so they cost nobody else any:

1. **Auto-configuration is split per technology.** `flyway-core` on the classpath
   is *not* enough — `org.springframework.boot:spring-boot-flyway` must be a
   dependency or **migrations silently never run**. Clean startup, empty schema.
2. **Jackson 3.** Databind and core moved to `tools.jackson.*`; **annotations
   stayed** at `com.fasterxml.jackson.annotation`. Mixed namespaces in one file
   are correct, not a mistake.
3. **`starter-web` no longer pulls JSON in transitively** — declare
   `spring-boot-starter-json` where you use Jackson directly.
4. **Testcontainers is not managed by Boot 4's BOM**, and Testcontainers 2.x
   renamed artifacts (`postgresql` → `testcontainers-postgresql`).
5. **`@EntityScan`/`@EnableJpaRepositories` moved packages.** Default scanning
   from the application package covers our layout.
6. **Resilience4j** ships a distinct `-spring-boot4` artifact.

---

## 13. Testing strategy

- Domain logic: unit tests, no Spring context
- Services: integration tests; PostgreSQL via Testcontainers in CI
- Architecture: ArchUnit in every module, rules shared from `platform-common`
- Contracts: Spectral lint; contract tests between services from Phase 1
- Tests are deterministic — no `Thread.sleep`, no real clock, inject a `Clock`

**Must always be covered** (§9 of the brief): ledger invariant, concurrent
reservation safety, ordered locking without deadlock, idempotent replay, replay
with mismatched body, reservation expiry mid-flight, average cost, cost frozen at
sale, missing cost, both return axes, over-return rejection, import preview,
re-import producing no duplicates, projection replay determinism, OAuth callback
with a bad state parameter, credential redaction.

---

## 14. Known issues and technical debt

Live register. Add an entry whenever you take a shortcut, hit a limitation, or
defer something. **Never silently work around a problem.**

| # | Issue | Why it exists | Blocks | Intended fix |
|---|---|---|---|---|
| 1 | Key file permissions are not restricted on Windows | POSIX permissions do not apply on the primary target platform; the file inherits directory ACLs | Nothing yet; weakens the threat model for stored OAuth tokens | Set Windows ACLs explicitly during Phase 7 install |
| 2 | Maven wrapper caches its distribution under `C:\Users\…\.m2\wrapper` | The local repo was moved to `D:` via `settings.xml`, but the wrapper's own cache path is separate | Nothing; conflicts with the no-artifacts-on-C: preference | Set `MAVEN_USER_HOME`, or accept and document |
| 3 | Service domain ArchUnit rules use `allowEmptyShould(true)` | Phase 0 services have no `domain` package, and ArchUnit correctly refuses to pass a rule that checked nothing | Nothing; the rules are inert until Phase 1 | Remove the relaxation when the first domain class lands — a tripwire test fails at that moment to force it. **Open question: whether to simply delete these rules until Phase 1 instead** |
| 4 | Contract tests between services do not exist | Nothing meaningful crosses a service boundary until Phase 1 | Phase 0 DoD item is partially unmet | Add with the first real cross-service call in Phase 1 |
| 5 | Generated OpenAPI clients are not yet wired to `ServiceClient` | The in-process binding dispatches by logical operation, while generated clients speak HTTP | Nothing yet | Reconcile in Phase 1, when the first generated client is used |
| 6 | **Tracing produces no spans.** `Tracer` exists but `currentSpan()` yields empty ids | Spring Boot 4 modularised observability auto-configuration; adding `spring-boot-micrometer-tracing`, `spring-boot-opentelemetry`, the OTel SDK and an explicit `ServerHttpObservationFilter` did not resolve it | **A Phase 0 Definition-of-Done item.** §3 requires a trace id in every log line and across every service; it is currently blank. Support diagnosis depends on it, and the `supportId` in every error response is therefore empty | Investigate the Boot 4 observability wiring properly — likely a missing bridge between `ObservationRegistry` and the OTel `SdkTracerProvider`. The smoke test asserting it is `@Disabled` with this issue number, deliberately visible rather than deleted |

---

## 15. Open questions

Mirrored from `BUILD_PROMPT.md` §14. **Do not guess at these.**

| # | Question | Blocks |
|---|---|---|
| 14.1 | How are purchase extra costs (freight, packing) allocated across lines? | Phase 2 |
| 14.2 | Are marketplace fees reversed on a return? Does it differ by return type? | Phase 3 |
| 14.3 | Does Flipkart accept a `127.0.0.1` OAuth callback? | Phase 6 |
| 14.4 | Real sample files: supplier Excel, Flipkart sales report per firm | Phase 4 |
| 14.5 | Accounting treatment of courier vs customer returns | Phase 5 |
| 14.6 | Opening stock: how is day-one inventory entered, and at what cost? | Phase 2 |
| 14.7 | Is a sale ever edited or cancelled after posting? | Phase 3 |

---

## 16. Deferred scope and the seam left for each

| Deferred | Seam |
|---|---|
| Tax / GST | None deliberately — no speculative columns. The courier/customer return distinction is preserved for its own reasons |
| Authentication | `tenant_id` on every table; `TenantContext` is the single place a principal is resolved |
| Multi-currency | None. Single currency, no currency column; would be a schema change |
| Licensing | Config property only |
| Pharma vertical | Capability flags (`batchTracking`, `expiryTracking`) exist in the manifest model, unimplemented |
| Cloud sync | `ServiceClient` HTTP binding, transactional outbox, PostgreSQL-portable migrations |
| Mobile app | Read-only cloud snapshot, Phase 9 |
