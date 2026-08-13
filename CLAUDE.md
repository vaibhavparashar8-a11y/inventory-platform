# Claude Code Instructions for this project

## What this app is

A local-first **inventory, sales and profitability platform** for small
retailers, built as a **commercial product** — not a one-off script. The first
customer is a single garment seller on one Windows PC; the same codebase is sold
to other sellers and extended to other product verticals. Judge every decision
by: *does this still hold at 50 customers and a second vertical?*

Eight services, one shared stock pool, sold through configurable *firms*
(marketplace seller accounts + an offline counter). It ships as **one JVM on one
PC** for the shopkeeper and as **eight containers** in the cloud, from one
codebase. Full brief: `BUILD_PROMPT.md`. Deep reference: `docs/DEVELOPER_GUIDE.md`.

The customer is non-technical with no IT support. Every failure mode either
self-heals or produces a message a shopkeeper can act on. **"The user will
reconcile it manually" is never an acceptable design.**

## Folder map

Marked `[P0]`…`[P7]` by the phase that creates it — see `BUILD_PROMPT.md` §8.
Do not create a module before its phase.

```
contracts/              ← OpenAPI specs, written BEFORE implementations   [P0]
platform-common/        ← tracing, RFC 9457 errors, idempotency, Money/Quantity,
                          ServiceClient SPI, outbox, credential encryption  [P0]
pack-api/               ← VerticalPlugin SPI + manifest model              [P1]
channel-api/            ← ChannelConnector SPI + normalised channel model  [P1]
services/
├── gateway/            ← serves the React bundle, routes /api/v1/**, the only
│                         port the browser talks to                        [P0]
├── catalog-service/    ← items, variants, categories, firms, pack manifests [P0]
├── stock-service/      ← the ledger — SOLE WRITER of quantity             [P0]
├── purchase-service/   ← supplier invoices, weighted average costing      [P2]
├── sales-service/      ← orders, offline counter, end-of-day batch        [P3]
├── returns-service/    ← returns on both axes, write-offs                 [P3]
├── channel-service/    ← file import AND API fetch, OAuth, normalisation  [P1]
└── reporting-service/  ← dashboard read model, P&L projections            [P5]
packs/pack-apparel/     ← manifest + optional plugin                       [P1]
connectors/
├── connector-file/     ← Excel/CSV inbound adapter                        [P4]
└── connector-flipkart/ ← Flipkart Seller API — NOT before Phase 6         [P6]
launcher/               ← composite single-JVM launcher for desktop mode   [P0]
web-ui/                 ← React + Vite + TypeScript + TanStack Query       [P1]
packaging/              ← jlink/jpackage Windows installer                 [P7]
docs/DEVELOPER_GUIDE.md ← the deep reference (schema, invariants, data flows)
docs/adr/               ← one ADR per significant decision
```

Each service is layered: `domain` (pure Java, **zero framework imports**) →
`application` (use cases, transaction boundaries) → `adapter` (JPA, HTTP,
messaging) → `api` (controllers, DTOs). ArchUnit enforces this in every module.

## Non-negotiable rules

These are architectural invariants, not preferences. Breaking one is a design
failure to stop and flag, not a shortcut to take.

1. **`stock-service` is the sole writer of stock quantity.** No other service has
   a stock table, a quantity column, or any way to change on-hand.
2. **Never write a saga or compensating transaction for stock.** Multi-step
   operations use two-phase reservations (`reserve → commit/release`, TTL 120s).
   If you find yourself writing one, the design has drifted — stop and flag it.
3. **Reservations are outbound-only.** Stock *increases* cannot violate the
   invariant, so they post a single idempotent movement call. Never wrap an
   increase in a reservation.
4. **The ledger is append-only.** No update, no delete on `stock_movement`.
   Mistakes are corrected by an `ADJUSTMENT` row with a reason code.
5. **Never special-case "clothes" outside `pack-apparel`, or "Flipkart" outside
   `connector-flipkart`.**
6. **Never invent a business rule**, especially around money or stock quantities.
   Ask, and add the question to `BUILD_PROMPT.md` §14.
7. **Tax/GST is out of scope** — no tax fields, no HSN codes, not even
   speculative columns "just in case".

## Conventions

- **Layering:** api → application → domain, with adapters at the edges. Domain
  imports no framework. Controllers contain no business logic. DTOs at every API
  boundary — never expose a JPA entity.
- **`platform-common` is infrastructure only.** No business types. The one
  exception is money, which is a value type, not a rule. A business rule
  appearing there means services are coupled through the back door — flag it.
- **Database per service.** No shared tables, no cross-service joins, no foreign
  keys across service boundaries. Reference by id only.
- **Contract-first.** The OpenAPI spec in `contracts/` is written before the
  implementation; clients are generated, never hand-written.
- **Constructor injection only.** No field injection.
- **Records for DTOs**; immutable value objects wherever practical.
- **Two deployment shapes, one codebase.** The `InProcessServiceClient` still
  serialises DTOs, propagates trace context and the idempotency key, applies
  timeouts, and **starts a new transaction in the callee** — it never joins the
  caller's, or desktop mode would hide bugs that only appear in cloud.

## Correctness details that are easy to get wrong

- **Locking:** pessimistic write lock on balance rows inside one transaction,
  rows locked in **ascending `variant_id` order** so deadlock is structurally
  impossible. `@Version` is a second line of defence, not the mechanism.
- **Available = `on_hand - reserved`.** Outbound checks `available`, never `on_hand`.
- **Money:** `BigDecimal` only, never `double`. Prices/fees/totals **scale 2**;
  unit costs and weighted averages **scale 4**. Never round intermediates. No raw
  `BigDecimal` in the domain — use `Money` / `Quantity`.
- **Time:** store UTC always. Date-range filters use the tenant's configured
  **business day**, not UTC midnight.
- **`costAtSale` is frozen at sale.** If it cannot be resolved, store `null` and
  flag `costMissing` — **never substitute zero**; zero cost reports 100% margin
  as fact.
- **Returns have two independent axes**: type (`COURIER_RETURN` /
  `CUSTOMER_RETURN`) and disposition (`RESTOCK` / `DAMAGED` / `LOST`). Two
  separate enums, never collapsed.
- **Idempotency:** `Idempotency-Key` header, scope `(tenant_id, endpoint, key)`.
  Replay returns the stored response verbatim; in-flight duplicate → 409; same
  key with a different body → 422.
- **Imports are idempotent via natural keys**, not cleverness. Sale line key:
  `(firmId, channelId, externalOrderId, externalShipmentRef, externalLineId)`.
- **Every query filters `tenant_id`** — enforced by a Hibernate filter, not by
  developer discipline.

## Coding Standards & Best Practices — follow on every change

Spotless and ArchUnit are wired into the build as **gates**, not advice.
`./mvnw verify` must be green before you commit.

**Java idioms**
- `final` for locals and fields that never reassign; `var` only when the type is
  obvious from the right-hand side.
- Records for DTOs and value objects; sealed interfaces + pattern matching for
  closed domain hierarchies (Java 21).
- `Optional` as a return type, never as a field or parameter.
- Prefer streams where they read more clearly than a loop — not reflexively.
- Name things descriptively and match the surrounding file's naming and comment
  density.

**Null-safety & async**
- Don't return `null` from a domain method; return `Optional` or throw a domain
  exception.
- Every inter-service and outbound call has a timeout — no unbounded waits.
- Retry with backoff **only** on idempotent operations.

**Error handling (the rule that matters most here)**
- **Never write a bare `catch (Exception e) {}` that hides a failure.** If a
  failure is non-fatal, still log it with context. Silent catches mask exactly
  the bugs this architecture exists to prevent — a swallowed stock-movement
  failure is a wrong stock count the customer discovers weeks later.
- Every failure surfaced to the user is plain language with a support id that
  maps to the trace id — never a stack trace.
- One `@RestControllerAdvice` per service returning RFC 9457 problem details
  with stable machine-readable `type` URIs the frontend can switch on.

**Structure**
- Business logic lives in `application`/`domain`, never in a controller or a
  repository.
- Domain classes import no Spring, no JPA, no Jackson.
- New service → follow the "How to add a new service" checklist in the developer
  guide (contracts, tracing, migrations, launcher registration, contract tests,
  tenant filtering).

**File size — keep files small enough for a newcomer to grasp**
- Treat **~400 lines** as a soft ceiling and **~500** as a hard smell for any
  single Java file. A file a new developer can't skim in a few minutes is too big
  — split it before adding more.
- Split by responsibility, not by arbitrary line count: extract a use case into
  its own application service, a mapper into its own class, a query into its own
  repository method. Push logic down into the domain so controllers stay thin.
- The same spirit applies to migrations and React components.

**Before every commit**
- `./mvnw verify` green (tests, ArchUnit, Spotless), and — per the mandatory
  sections below — tests + `docs/DEVELOPER_GUIDE.md` updated in the same PR.

## Commands

| Task | Command |
|---|---|
| Build + test + gates | `./mvnw verify` |
| Fast build, skip tests | `./mvnw -DskipTests package` |
| Run one module's tests | `./mvnw -pl services/stock-service test` |
| Fix formatting | `./mvnw spotless:apply` |
| Run desktop mode (all services, one JVM) | `./mvnw -pl launcher spring-boot:run` |
| Run one service standalone | `./mvnw -pl services/<name> spring-boot:run` |
| Regenerate API clients from OpenAPI | `./mvnw generate-sources` |
| Frontend dev server | `cd web-ui && npm run dev` |

Always use `./mvnw` — never a global `mvn`. The wrapper pins Maven 3.9.16.

## Git Workflow — MANDATORY, never skip

Every change must follow this flow, no exceptions:

1. `git checkout -b <prefix>/<short-description>` — always branch from current base
   - `feat/` for new features
   - `fix/` for bug fixes
   - `chore/` for non-code changes (deps, config, etc.)
   - For phase work, use the brief's own convention: `feature/<phase>-<short-name>`
2. Make changes, commit to the branch (Conventional Commits: `feat:`, `fix:`,
   `refactor:`, `docs:`, `test:`, `chore:`)
3. `git push origin <branch>`
4. Create a PR — never merge directly into main
5. User merges the PR on GitHub
6. **After every merge into main: run `git fetch origin && git pull origin main`**
   to bring local in sync — confirm with `git status` showing "up to date"

**Never commit directly to `main`.**
**Never push directly to `main`.**
Each logical unit of work (feature or fix) gets its own branch and PR.

At the end of each phase: annotated tag (`v0.1.0-foundation`, …) and a
`CHANGELOG.md` entry.

## Testing — MANDATORY, never skip

Every feature and bug fix must include tests:

- New feature → unit tests for domain logic (no Spring context) plus an
  integration test for the adapter/API path
- Bug fix → a test that would have caught the bug (regression test)
- Tests mirror the source tree (`…/domain/Foo.java` → `…/domain/FooTest.java`)
- Contract tests between services — not optional in a microservice system
- `./mvnw verify` green before committing
- Tests are deterministic: no `Thread.sleep`, no real clock — inject a `Clock`
- If a feature genuinely cannot be tested, document why in the PR description —
  do not silently skip

**These must always be covered** (from `BUILD_PROMPT.md` §9): ledger invariant,
concurrent reservation safety, ordered locking with no deadlock, idempotent
replay, replay with mismatched body, reservation expiry mid-flight, average cost
calculation, cost-frozen-at-sale, missing-cost handling, both return axes,
over-return rejection, import preview matching, re-import producing no
duplicates, projection replay determinism, OAuth callback with a bad state
parameter, credential redaction in serialisation.

## Documentation — MANDATORY, never skip

Every change that adds, removes, or alters behaviour must update
`docs/DEVELOPER_GUIDE.md` **in the same PR** as the code change:

- New feature → update the feature catalogue (§2) with its state and phase, and
  the relevant module section
- New service → update the service map (§3), the "add a new service" checklist
  (§9), and launcher registration
- Schema change → update the domain model section (§6) and note the migration
- New invariant or protocol change → update §5 (stock rule / reservation
  protocol) or §6 (per-service invariants)
- Shortcut, limitation, or deferral → **add a numbered entry to the known-issues
  register (§14)**: what it is, why it exists, what it blocks, the intended fix
- New open question → add to §15, mirrored from `BUILD_PROMPT.md` §14
- Significant decision → add an ADR in `docs/adr/` (context, options, decision,
  consequences)

Pure refactors with no behaviour change need no docs update — but check whether
any guide snippet references the moved code.

**A phase is not complete until the guide reflects it.**

## Environment

- JDK 21 (Microsoft build) — `java -version` must report 21
- Node 24 / npm 11 for `web-ui`
- Maven supplied by the committed wrapper; Maven 4 is still RC, stay on 3.9.16
- Docker is **not** installed locally, so Testcontainers/PostgreSQL runs in CI
  only. Local tests use H2; CI proves migrations against real PostgreSQL.
- Prefer keeping build artifacts and caches off the C: drive where practical
  (see known issue: the Maven local repo currently defaults to `C:\Users\…\.m2`)

## Security

- The repository is **PUBLIC**. Never commit customer data, real marketplace
  reports, API credentials, tokens, or the `data/` folder. A committed secret is
  permanent — rewriting history does not un-publish it.
- A secret-scanning pre-commit hook is enabled via
  `git config core.hooksPath .githooks`. It is a safety net, not a substitute for
  care. Bypass only with `ALLOW_SECRET=1` and only when certain.
- OAuth tokens encrypted at rest (AES-GCM, key outside the database, key-id
  column for rotation), never logged, never in a trace, never returned by any API.
- Desktop mode binds `127.0.0.1` only, and the gateway validates `Origin`/`Host`
  — binding to localhost is not a security boundary on its own.
- Parameterised queries only. Validate and size-limit every upload; guard against
  XXE and zip bombs on spreadsheet import.

## Workflow

- **One phase at a time.** Plan → confirm → build → prove → record → report.
  Never start the next phase without explicit confirmation.
- **Start Fresh:** run `/clear` at the beginning of any brand new feature or task
  to prevent context bloat and stop prior session history causing hallucinations.
- **Agent Orchestration:** do not fan out agents until I explicitly specify.
  Always ask whether fanning out is necessary before doing so.
- **Look Before You Leap:** before modifying any core file (especially
  `stock-service`, the launcher, or `platform-common`), grep/read the relevant
  sections of `docs/DEVELOPER_GUIDE.md` first.
- **Surgical Changes Only:** modify exclusively the code required for the task.
  Do not touch adjacent working logic, reformat unrelated blocks, or clean up
  styling unless explicitly requested.
- **Do everything that is not blocked.** If one open question blocks part of a
  phase, build the rest, state the assumption, and flag it — do not stall the
  whole phase on one answer.
- **Evidence, not claims.** A phase is done when tests have actually run and the
  output is shown. Never report completion for code that has not executed.
- **Log every assumption and shortcut** in the known-issues register. Do not
  silently work around a problem.
