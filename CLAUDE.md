# CLAUDE.md — working rules for this repository

Distilled from `BUILD_PROMPT.md`, which remains the authority. If the two ever
disagree, `BUILD_PROMPT.md` wins and this file is wrong — fix it.

## What this is

A local-first inventory, sales and profitability platform for small retailers.
First customer: one garment seller on one Windows PC. Same codebase is sold to
other sellers and extended to other verticals. Judge every decision by: *does
this still hold at 50 customers and a second vertical?*

The customer is non-technical with no IT support. Every failure mode either
self-heals or produces a message a shopkeeper can act on. **"The user will
reconcile it manually" is never an acceptable design.**

## Non-negotiable rules

1. **`stock-service` is the sole writer of stock quantity.** No other service has
   a stock table, a quantity column, or any way to change on-hand. Others call it.
2. **Never write a saga or compensating transaction for stock.** If you find
   yourself doing so, stop and flag it — the design has drifted. Multi-step
   operations use two-phase reservations (`reserve → commit/release`, TTL 120s).
3. **Reservations are outbound-only.** Stock *increases* cannot violate the
   invariant, so they post a single idempotent movement call. Do not wrap an
   increase in a reservation.
4. **The ledger is append-only.** No update, no delete on `stock_movement`.
   Mistakes are corrected with an `ADJUSTMENT` row carrying a reason code.
5. **Never special-case "clothes" outside `pack-apparel`, or "Flipkart" outside
   `connector-flipkart`.** That is a design failure, not a shortcut.
6. **Never invent a business rule**, especially around money or stock. Ask, and
   add the question to §14 of `BUILD_PROMPT.md`.
7. **Tax/GST is out of scope.** No tax fields, no HSN codes, not even speculative
   columns.

## Correctness details that are easy to get wrong

- **Locking:** pessimistic write lock on balance rows, in one transaction, rows
  locked in **ascending `variant_id` order** so deadlock is structurally
  impossible. `@Version` is a second line of defence, not the mechanism.
- **Available = `on_hand - reserved`.** Outbound checks `available`, never `on_hand`.
- **Money:** `BigDecimal` only. Prices/fees/totals **scale 2**; unit costs and
  weighted averages **scale 4**. Never round intermediates. No raw `BigDecimal`
  in the domain — use `Money` / `Quantity`.
- **Time:** store UTC always. Date-range filters use the tenant's configured
  **business day**, not UTC midnight.
- **`costAtSale` is frozen at sale.** If it cannot be resolved, store `null` and
  flag `costMissing` — **never substitute zero** (zero cost reports 100% margin
  as fact).
- **Returns have two independent axes**: type (`COURIER_RETURN` /
  `CUSTOMER_RETURN`) and disposition (`RESTOCK` / `DAMAGED` / `LOST`). Two enums.
  Never collapse them.
- **Idempotency:** `Idempotency-Key` header, scope `(tenant_id, endpoint, key)`.
  Replay returns the stored response verbatim; in-flight duplicate → 409;
  same key with a different body → 422.
- **Imports are idempotent via natural keys**, not via cleverness. Sale line key:
  `(firmId, channelId, externalOrderId, externalShipmentRef, externalLineId)`.
- **Every query filters `tenant_id`** — enforced by a Hibernate filter, not by
  developer discipline.

## Architecture invariants

- Database per service. No shared tables, no cross-service joins, no FKs across
  service boundaries. Reference by id only.
- Hexagonal layering per service: `domain` (pure Java, zero framework imports),
  `application`, `adapter`, `api`. Enforced by ArchUnit in every module.
- `platform-common` is **infrastructure only** — no business types. The exception
  is money, which is a value type, not a rule.
- Contract-first: OpenAPI in `contracts/` is written **before** the
  implementation; clients are generated, never hand-written.
- Eventual consistency exists **only** in `reporting-service`, fed by a
  transactional outbox. Everything else is synchronous and strongly consistent.
- Two deployment shapes, one codebase: desktop = all services in one JVM via the
  composite launcher + `InProcessServiceClient`; cloud = containers + HTTP. The
  in-process client still serialises DTOs, propagates trace context, applies
  timeouts, and **starts a new transaction in the callee** — never joins the
  caller's.

## Toolchain (verified Phase 0, pinned — do not float)

Java 21 · Spring Boot 4.1.0 · Maven 3.9.16 (wrapper committed) · Flyway 12.4.0 ·
H2 2.4.240 local / PostgreSQL 42.7.11 in CI · Micrometer Tracing 1.7.0 ·
Testcontainers 2.0.5 (explicit BOM; Boot 4 does not manage it, and 2.x renamed
artifacts to `testcontainers-*`) · Resilience4j 2.4.0 `-spring-boot4` ·
ArchUnit 1.4.1 · Spotless 3.9.0 · OpenAPI Generator 7.24.0.

Build with `./mvnw` (never a global `mvn`). Formatting is a gate: `mvn
spotless:apply` to fix, `verify` fails on drift.

## Working agreement

- **One phase at a time.** Plan → confirm → build → prove → record → report.
  Never start the next phase without confirmation.
- **Do everything that is not blocked.** If one open question blocks part of a
  phase, build the rest and flag the assumption. Do not stall the whole phase.
- **Evidence, not claims.** A phase is done when tests have actually run and the
  output is shown. Never report completion for code that has not executed.
- **Log every assumption and shortcut** in the known-issues register in
  `docs/DEVELOPER_GUIDE.md`. Do not silently work around a problem.
- **This repo is public.** Never commit customer data, real marketplace reports,
  credentials or tokens. A `.githooks/pre-commit` secret scan is enabled via
  `core.hooksPath`; it is a safety net, not a substitute for care.
- Conventional Commits; short-lived `feature/<phase>-<name>` branches; annotated
  tag at the end of each phase.
