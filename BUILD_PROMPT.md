# Build Prompt — Multi-Vertical Inventory & Sales Platform (Microservices)

> **Document version 2.0** — supersedes v1. Paste this whole file as the opening
> instruction to your AI coding assistant (Claude Code, Copilot Agent, Cline,
> Junie, etc.) in VS Code or IntelliJ. Work through it **one phase at a time**.
> Do not skip ahead.

---

## 0. How to use this document

**Read §1–§13 before writing any code. Then implement Phase 0 only.**

Every phase follows the same loop:

1. **Plan** — post a short plan (what you will build, which files, which tests,
   which open questions block you). Wait for confirmation.
2. **Build** — code, tests, migrations, docs together. Not code first, tests later.
3. **Prove** — run the Definition of Done checklist below and paste the evidence
   (test output, trace id, screenshot). Claims without evidence do not count.
4. **Record** — update `docs/DEVELOPER_GUIDE.md`, `CHANGELOG.md`, the known-issues
   register and the open-questions register. Commit. Tag.
5. **Report** — a short summary: what was built, what was assumed, what was
   skipped and why, what you need from me before the next phase.

### Definition of Done — applies to *every* phase

A phase is not complete until all of these are true:

- [ ] All new endpoints exist in `contracts/` **before** the implementation, and
      the generated client compiles against them
- [ ] Unit tests for domain logic pass with no Spring context
- [ ] Integration tests pass for every new service
- [ ] Contract tests pass between every pair of services that now talk
- [ ] ArchUnit domain-purity test passes in every module
- [ ] Flyway migrations run cleanly on **an empty database and on the previous
      phase's database** (upgrade path proven, not assumed)
- [ ] The **co-located single-JVM launcher still boots** and the smoke test passes
      in CI (this is the deployment story; it must never silently rot)
- [ ] One end-to-end trace id is visible across every service touched by the new
      feature
- [ ] `DEVELOPER_GUIDE.md`, `CHANGELOG.md`, known-issues and open-questions
      registers updated
- [ ] Build is green in GitHub Actions; formatter and linter enforced, not advisory
- [ ] Annotated git tag pushed

### A note on version numbers in this document

Library and framework versions below were correct when this was written and may
be stale. **Before Phase 0, check the current stable release of each pinned
dependency and tell me what you found** rather than silently using the number
here or silently using something newer. Pin exact versions in the build; do not
use dynamic version ranges.

---

## 1. Your role

You are a senior backend architect and full-stack engineer building a
**commercial product**, not a one-off script. The first customer is a single
garment seller running on one Windows PC, but the same codebase will be sold to
other sellers and extended to other product verticals. Judge every decision by:
*does this still hold at 50 customers and a second vertical?*

Prefer boring, well-understood patterns. When a requirement is genuinely
ambiguous — especially around money or stock quantities — **stop and ask** rather
than inventing a business rule. §14 already lists the questions known to be open;
add to it whenever you find another.

The customer is non-technical and there is no on-site IT support. Every failure
mode must either self-heal or produce a message a shopkeeper can act on. "The
user will reconcile it manually" is never an acceptable design.

---

## 2. What the product does

A local-first inventory, sales and profitability system for a small retailer.

- **Purchases** are entered by form or Excel upload and raise stock.
- **Sales** happen through configurable *firms*: two Flipkart seller accounts and
  an offline counter, all drawing from **one shared stock pool**. Marketplace
  sales arrive either by uploaded report **or by direct API fetch** (see §7);
  offline sales are entered directly, both at the counter in real time and as an
  end-of-day batch.
- **Returns** are recorded against a sale line and reduce or restore stock.
- A **dashboard** reports sales, returns, write-offs and profit & loss.

### Scale assumptions — design to these, do not over-engineer past them

| Dimension | Year 1 (one customer) | Design headroom |
|---|---|---|
| Variants (SKUs) | ~5,000 | 50,000 |
| Sale lines / day | ~300 | 5,000 |
| Sale lines total | ~100,000 | 2,000,000 |
| Concurrent users | 1–3 | 10 |
| Largest import file | ~5,000 rows | 50,000 rows |

### Non-functional targets — these are acceptance criteria, not aspirations

- Counter sale posts and confirms in **< 300 ms** at p95 on the target PC
- Dashboard loads in **< 2 s** at p95 with 100,000 sale lines
- 10,000-row import parses and previews in **< 30 s**, streaming, without loading
  the whole file into memory
- Cold start of the desktop app to usable UI in **< 20 s**
- Idle memory footprint of the single JVM under **1 GB**

Write a load/perf smoke test for the first two once Phase 5 exists. If a target
is missed, record it in the known-issues register — do not quietly accept it.

---

## 3. Service decomposition

```
gateway             serves the React bundle, routes /api/v1/** to services,
                    the single port the user's browser talks to
catalog-service     items, variants, categories, firms, vertical pack manifests
stock-service       the stock ledger — SOLE WRITER of quantity
purchase-service    supplier invoices, weighted average costing
sales-service       orders, offline counter, end-of-day batch entry
returns-service     returns on both axes, write-offs
channel-service     marketplace integration: file import AND API fetch,
                    credential storage, OAuth, normalisation, preview
reporting-service   dashboard read model, P&L projections
```

### The single most important rule in this system

**`stock-service` is the sole writer of stock quantity.** No other service has a
stock table, a quantity column, or a way to change on-hand. Purchase, sales and
returns services own their own business data in their own databases, and call
`stock-service` over HTTP to move quantity.

This is deliberate and not negotiable. "Stock never goes negative" is an
invariant that must hold at every instant, not eventually. Keeping the ledger
inside one service means one local database transaction enforces it. Two
concurrent sales both call `stock-service`; it serialises them and rejects one
**before anything is committed anywhere**. There is no provisional state to undo,
so there are no compensating transactions for stock.

**Do not implement sagas or compensating transactions for stock movements.** If
you find yourself writing one, the design has drifted — stop and flag it.

### How the invariant is actually enforced — implement exactly this

Ambiguity here produces subtly broken concurrency, so it is specified rather than
left to judgement:

- `stock_balance` has **one row per (tenant_id, variant_id)**, created on first
  use, with a unique constraint on that pair.
- Available quantity is **`available = on_hand - reserved`**. Reservations and
  outbound movements check `available`, never `on_hand`.
- Every quantity-changing transaction takes a **pessimistic write lock**
  (`SELECT … FOR UPDATE`, via `@Lock(PESSIMISTIC_WRITE)`) on the balance rows it
  touches, inside one local transaction. Optimistic `@Version` is kept as a
  second line of defence, not as the primary mechanism.
- A multi-line operation locks rows in **ascending `variant_id` order** to make
  deadlock structurally impossible. This is a hard rule; write a test for it.
- The check and the write happen in the same transaction. Never read the balance,
  return to the caller, and write later.

This is the reason the storage engine must support row-level locking — see §5.

### Reservation pattern

Multi-step operations use two-phase reservations, not sagas:

1. `POST /api/v1/reservations` — stock-service holds quantity, returns a
   reservation id and a TTL (default 120s)
2. The calling service commits its own business record in its own local transaction
3. `POST /api/v1/reservations/{id}/commit` — the ledger row is written
4. If step 2 fails, call `/release`; if the caller dies, the TTL expires and the
   quantity returns automatically

Expiry means an orphaned reservation self-heals. There must be no manual
reconciliation screen for stock — the customer is non-technical.

**Reservation rules, specified:**

- Expiry is evaluated **at read time as well as by the sweeper**. An expired but
  not-yet-swept reservation must never block a sale. The sweeper (every 10s) is
  housekeeping, not correctness.
- Committing an expired or released reservation returns **409** with a distinct
  problem type (`urn:problem:reservation-expired`) so the caller can retry the
  whole operation cleanly.
- `commit` and `release` are **idempotent**: replaying a commit returns the
  original result, never a second ledger row.
- **Reservations are only for outbound (stock-decreasing) flows.** A purchase or
  a restock *increases* stock and can never violate the invariant, so it posts a
  single idempotent `POST /api/v1/movements` call. Do not wrap increases in a
  reservation — it is ceremony with no invariant behind it.
- State transitions are `HELD → COMMITTED | RELEASED | EXPIRED` only. Terminal
  states never change again.

### Cross-cutting requirements for every service

- **Database per service.** No shared tables, no cross-service joins, no foreign
  keys across service boundaries. Services reference each other by id only.
- **Idempotency key required** on every state-changing call to `stock-service`.
  Retries are certain; double-decrements are not acceptable. Store processed keys
  and return the original result on replay. Specifically:
  - header name `Idempotency-Key`, caller-generated UUID
  - uniqueness scope is `(tenant_id, endpoint, idempotency_key)`
  - the stored record holds the response body and status; a replay returns them
    verbatim
  - a **concurrent** request with the same key that is still in flight returns
    **409**, it does not block or duplicate
  - a replay with the same key but a *different* request body returns **422** —
    that is a caller bug and must be loud
  - stored keys are retained 7 days, then purged by a scheduled job
- **Natural keys for everything imported.** Any record that can arrive twice
  carries a unique natural key and a unique index on it. See §7.
- **Distributed tracing from day one** (Micrometer Tracing + OpenTelemetry). Not
  retrofitted. A W3C `traceparent` must flow from the browser through the gateway
  to every service and appear in every log line.
- **Contract-first.** The OpenAPI spec is written before the implementation and
  lives in `contracts/`. Clients are generated, never hand-written. Specs are
  linted (Spectral or equivalent) and checked for breaking changes in CI.
- **Eventual consistency is confined to `reporting-service`.** It consumes events
  and may lag. The UI must show an explicit "updating" state rather than
  presenting a stale number as fact. Everything else is synchronous and strongly
  consistent.
- **Events are published via a transactional outbox**, from Phase 5 onward. A
  service writes its business row and its outbox row in the same local
  transaction; a relay publishes them. Never publish an event from inside a
  transaction that may still roll back, and never publish "best effort" after
  commit. In desktop mode the transport is an in-process bus; in cloud mode it is
  the broker. Same outbox, two transports — mirroring `ServiceClient`.

### Deployment topology — one codebase, two shapes

The desktop customer cannot run eight JVMs. Solve this in **deployment, not
architecture**:

- **Desktop mode:** all services co-located in a single JVM via a composite
  launcher (parent Spring context, each service as a child context), with an
  in-process transport binding. One process, one port, one installer.
- **Cloud mode:** eight containers, real HTTP, real message broker.

Service code is identical in both. Only the transport binding and the launcher
differ. Abstract the inter-service call behind a `ServiceClient` interface with
two implementations: `InProcessServiceClient` and `HttpServiceClient`.

**The in-process implementation must not silently change semantics.** It still
serialises and deserialises DTOs (so no accidental object sharing across service
boundaries), still propagates the trace context and the idempotency key, still
applies timeouts, and still starts a **new transaction** in the callee rather
than joining the caller's. If in-process calls join the caller's transaction, the
two deployment modes have different consistency behaviour and the desktop build
will hide bugs that only appear in cloud. Write a test that asserts this.

**Validate the co-located launcher in Phase 0.** Do not defer it to the packaging
phase — if it does not work, the entire deployment story collapses, and you need
to know that on day one rather than in month four. From Phase 0 onward, **CI runs
the launcher smoke test on every PR** in addition to the per-service tests.

---

## 4. Repository layout

```
inventory-platform/
├── contracts/              OpenAPI specs, shared DTO schemas, event schemas
├── platform-common/        tracing, error model, idempotency, ServiceClient SPI,
│                           money types, outbox, credential encryption
├── pack-api/               VerticalPlugin SPI + manifest model
├── channel-api/            ChannelConnector SPI + normalised channel model
├── services/
│   ├── gateway/
│   ├── catalog-service/
│   ├── stock-service/
│   ├── purchase-service/
│   ├── sales-service/
│   ├── returns-service/
│   ├── channel-service/
│   └── reporting-service/
├── packs/
│   ├── pack-apparel/       manifest + optional plugin
│   └── pack-toys/          (later phase)
├── connectors/
│   ├── connector-file/     Excel/CSV inbound adapter
│   └── connector-flipkart/ Flipkart Seller API adapter (later phase)
├── launcher/               composite single-JVM launcher for desktop mode
├── web-ui/                 React app
├── packaging/              jlink/jpackage, installer, desktop shortcut
└── docs/                   DEVELOPER_GUIDE.md, ADRs, diagrams
```

Each service internally follows hexagonal layering: `domain` (pure Java, no
framework imports), `application` (use cases, transaction boundaries), `adapter`
(JPA, HTTP, messaging), `api` (controllers, DTOs). Enforce the domain purity rule
with an ArchUnit test in every service.

**`platform-common` is infrastructure only.** No business types, no domain
concepts, no shared entities. The moment a business rule appears there, services
have become coupled through the back door — stop and flag it. The one deliberate
exception is money (§9), which is a value type, not a rule.

---

## 5. Locked technical decisions

| Area | Decision |
|---|---|
| Backend | **Java 21 (LTS)**, Spring Boot 3.5.x — verify current patch before Phase 0 |
| Frontend | React 18 + Vite + TypeScript + TanStack Query |
| API | Versioned REST at `/api/v1`, OpenAPI-first |
| Local storage | **H2 in file mode (default)**; PostgreSQL adapter behind the same code |
| Cloud storage | PostgreSQL, later phase |
| Migrations | Flyway per service, forward-only, run on startup |
| Multi-tenancy | `tenant_id` on **every** table from the first migration |
| Packaging | `jlink` + `jpackage` → single Windows installer, embedded JRE |
| Verticals | Pluggable **Vertical Packs**; apparel first, toys later |
| Channels | Pluggable **Connectors**; file first, Flipkart API later |
| Licensing | **Not now.** Packs chosen by build profile + config property |
| Tax / GST | **Out of scope.** Do not add tax fields or logic. See §13. |

### Two changes from v1 of this document, with reasons

**Java 17 → Java 21.** Java 21 is the current LTS, is the baseline Spring Boot is
optimising for, and gives virtual threads (which matter for a gateway fanning out
to eight in-process services) and pattern matching for the domain enums this
system is full of. Starting a multi-year commercial product on the older LTS buys
nothing. If you have a constraint that forces 17, say so and I will revert this.

**SQLite → H2 file mode as the local default.** The invariant in §3 depends on
row-level locking and `SELECT … FOR UPDATE`. SQLite has neither: it serialises at
the *whole database* level, has no first-party Hibernate dialect, and would make
"eight databases, one per service" into eight single-writer files with lock
contention under exactly the concurrent-sale scenario this design exists to
handle. H2 file mode gives real row locking, a supported dialect, and PostgreSQL
compatibility mode — so the local engine and the cloud engine behave the same and
the same migrations run on both. **Write the migrations in portable SQL and test
them against both H2 and PostgreSQL in CI from Phase 0.**

If you disagree with either change, argue it before Phase 0 rather than after.

---

## 6. Domain model

### catalog-service

- **Item** — product line. Name, vertical pack id, product type, brand, style code.
- **Variant** — one sellable unit = Item + variant axis values. Holds `skuCode`,
  `externalCode` (marketplace SKU/FSN), `reorderLevel`. **No quantity field.**
- **Category** — quality band. A colour's category is decided by its quality, and
  the category code forms part of the SKU code.
- **Firm** — a selling identity. Name, channel type (FLIPKART / OFFLINE /
  OTHER_ONLINE), seller code. **Fully CRUD-manageable from the UI** — never
  seeded constants.

`skuCode` is unique per tenant. `externalCode` is unique per `(tenant, firm)` —
the same physical variant can carry different marketplace codes on two seller
accounts, and that is the normal case, not an edge case.

**Nothing is ever hard-deleted** once it has been referenced by a movement, sale
or purchase. Use an `archived` flag; archived variants disappear from pickers but
remain resolvable by id for history. Deleting a variant that has a ledger history
would orphan the ledger — reject it with a clear message.

### stock-service

- **StockBalance** — `variantId`, `onHand`, `reserved`. A cache, rebuildable.
- **StockMovement** — append-only ledger. `variantId`, movement type, signed
  `qtyDelta`, `balanceAfter`, source service, source reference, timestamp.
- **Reservation** — `variantId`, qty, state (HELD / COMMITTED / RELEASED /
  EXPIRED), expiry timestamp, idempotency key.

Movement types: `PURCHASE`, `SALE`, `RETURN_RESTOCK`, `RETURN_DAMAGED`,
`RETURN_LOST`, `ADJUSTMENT`.

The ledger is **append-only and immutable**. There is no update and no delete on
`stock_movement` — a mistake is corrected by an `ADJUSTMENT` row, never by
editing history. Enforce it in code and, if the engine allows, with a trigger.
`ADJUSTMENT` requires a reason code and a free-text note; it is the only movement
type a human can create directly, and it must be visible in an audit view.

Provide a `rebuildBalances()` maintenance operation and a test proving the ledger
sum equals `onHand` for every variant. Nothing may write a balance without a
ledger row.

### purchase-service

- **Purchase / PurchaseItem** — supplier invoice, extra costs (freight, packing).
- Owns **weighted average cost per variant** and exposes it via API. A purchase
  raises stock through `stock-service`.

Weighted average cost is recomputed on each receipt as
`newAvg = (oldAvg × oldQty + landedCost × newQty) / (oldQty + newQty)`, carried at
**scale 4** (§9). Store a `cost_history` row on every change — historical cost is
audit data, and a single mutable "current cost" column is not enough to explain a
P&L number to a customer six months later.

**Open question — see §14.1:** how extra costs (freight, packing) are allocated
across lines. Do not guess.

### sales-service

- **Sale / SaleItem** — one order on one firm. `costAtSale` is **frozen** on the
  line at the moment of sale (fetched from purchase-service) so historical profit
  never silently changes when purchase costs move.
- Fields: `unitPrice`, `fees` (marketplace commission, shipping), `returnedQty`.
- **`shipmentRef` on the sale line.** Flipkart's order model is shipment-centric:
  one order can split across shipments, and quantities can be partially cancelled
  before dispatch. Without a shipment reference, API-fetched orders will
  double-count.

**Natural key on the sale line**, unique per tenant:
`(firmId, channelId, externalOrderId, externalShipmentRef, externalLineId)`.
This is what makes both file import and API fetch safe to re-run. Offline sales
get a generated key in the same shape. Add the unique index in the first
migration — retrofitting it onto duplicated data is far worse.

**If `costAtSale` cannot be resolved** (variant never purchased, e.g. opening
stock entered by adjustment), record the sale with `costAtSale = null` and flag
the line as `costMissing`. Do not substitute zero — zero cost silently reports
100% margin, which is a wrong number presented as fact. Surface these lines in a
dashboard panel so the customer can fix them.

### returns-service

Returns have **two independent axes**. This is easy to get wrong.

**Axis 1 — return type** (how far the sale got):
- `COURIER_RETURN` — never delivered (RTO). Supply never completed.
- `CUSTOMER_RETURN` — delivered, then sent back.

**Axis 2 — disposition** (physical condition; drives stock and write-off):
- `RESTOCK` — sellable, returns to stock
- `DAMAGED` — unsellable, written off at cost
- `LOST` — never arrived back, written off at cost

The axes are independent: a courier return can still come back damaged. Model
them as two separate enums. **Do not collapse them into one.** The distinction
has downstream accounting significance that is not yet specified — preserve it in
the data even though nothing consumes it yet.

Invariants: cumulative `returnedQty` on a sale line may never exceed the sold
quantity; a `RESTOCK` restores stock **at `costAtSale` of that line**, not at the
current average cost, so returning an item cannot move the average; write-offs
are valued at `costAtSale` too.

**Open question — see §14.2:** whether marketplace fees are reversed on return,
and whether that differs by return type. Do not guess.

### Vertical Packs

A pack is mostly declarative — ship a manifest, not code:

```yaml
id: apparel
name: Apparel & Garments
variantAxes:
  - { key: color, label: Colour, type: ENUM, indexed: true }
  - { key: size,  label: Size,   type: ENUM, indexed: true, ordered: true }
capabilities:
  batchTracking: false
  expiryTracking: false
  serialTracking: false
skuTemplate: "{style}-{category}-{color}-{size}"
dashboardWidgets: [colorSizeGrid, deadStockByColor]
```

Variant axis storage: a JSON attributes column on `Variant`, **plus** two generic
promoted columns (`attr1`, `attr2`) that are indexed, so grid queries stay fast.
Do **not** build a full EAV schema — it will make the dashboard slow.

The manifest is **versioned and validated on load** against a schema in
`contracts/`. A pack whose manifest fails validation refuses to load with a clear
error rather than half-loading. Changing a pack's axes after data exists is a
migration, not a config edit — record how that will work in the developer guide,
even though nothing needs it yet.

For behaviour a manifest cannot express, define a `VerticalPlugin` SPI in
`pack-api`: `skuCodeGenerator()`, `dashboardMetrics()`, `validators()`. Most packs
will not need it.

`catalog-service` owns the `PackRegistry` and serves `GET /api/v1/capabilities`.
The React app **renders its item forms from the manifest** — the frontend must
contain no hardcoded knowledge of "colour" or "size".

---

## 7. Channel integration — two inbound paths, one pipeline

Marketplace orders arrive two ways, and both must produce **identical** results:

```
  Excel / CSV upload  ─┐
                       ├─→  ChannelOrder (normalised)  →  preview  →  post to sales-service
  Flipkart API fetch  ─┘
```

Build the normalisation, preview and posting pipeline **once**. The two inbound
paths are adapters behind a common SPI. Never post to `sales-service` without a
preview step showing matched and unmatched SKUs.

**Every import is a durable `ImportBatch`** with an id, source, firm, row count,
and per-row outcome (`matched` / `unmatched` / `duplicate` / `posted` / `failed`).
A partially failed import must be resumable and must never post a row twice —
that is what the natural key in §6 guarantees. The customer must be able to open
a past import and see exactly what it did.

### `ChannelConnector` SPI (in `channel-api`)

```java
public interface ChannelConnector {
    String channelId();
    ConnectorCapabilities capabilities();      // supportsOrderFetch, supportsReturnFetch, supportsOAuth
    AuthorizationRequest beginAuthorization(FirmId firmId);
    ChannelCredential completeAuthorization(String code, String state);
    ChannelCredential refresh(ChannelCredential existing);
    List<ChannelOrder>  fetchOrders(FirmId firmId, Instant since, Instant until);
    List<ChannelReturn> fetchReturns(FirmId firmId, Instant since, Instant until);
}
```

`connector-file` implements only the fetch-free parts. `connector-flipkart`
implements the whole thing, later.

Fetch methods return a page-bounded result and must not materialise an unbounded
list — a two-year backfill would exhaust memory. Prefer a paged/streaming return
shape and settle it in Phase 1 while the SPI is cheap to change.

### OAuth scaffolding — build the frame now, the Flipkart implementation later

The user has asked that OAuth be structurally present from the start so adding
the real connector changes nothing in the core. Build these in **Phase 1**:

- **`ChannelCredential`** entity in `channel-service`, keyed to a Firm:
  encrypted access token, refresh token, expiry, scopes, connection state
  (`NOT_CONNECTED` / `CONNECTED` / `EXPIRED` / `ERROR`). The migration exists from
  day one — retrofitting encrypted credential storage across a live install is
  painful.
- **Credential encryption** in `platform-common`. AES-GCM with a key derived at
  install time and stored outside the database, in the OS user profile with
  restrictive ACLs. Include a **key-id column** so keys can be rotated later
  without a schema change. Tokens must never appear in logs, in traces, or in any
  API response — write a test that asserts a credential DTO serialises with the
  token redacted.
- **The OAuth callback endpoint** in `channel-service`, plus state-parameter CSRF
  validation (single-use, short-TTL, bound to the firm) and the token-exchange
  flow — working end to end against a **stub connector** you write for testing.
- **Token refresh scheduler**, driven by expiry, refreshing ahead of expiry with
  jitter and backoff, with a connection state that the UI can display. A failed
  refresh sets `EXPIRED`/`ERROR` and surfaces a plain-language prompt to
  reconnect; it must never silently retry forever.
- **A "Connections" tab on the Firm screen** showing per-firm connection state and
  a Connect button. With only `connector-file` registered, it shows "no API
  connector available for this channel" — correct, not broken.

Deliberately deferred to Phase 6: the concrete `connector-flipkart`. It needs
real seller credentials to test, and the response shapes cannot be guessed
reliably. Building it blind produces code you will throw away.

Design the callback handling so the redirect URI is **configuration, not code**
(see the open question in §14.3), so either answer works without a rewrite.

---

## 8. Phased build plan

Complete each phase fully — code, tests, docs, git tag — against the Definition of
Done in §0 before starting the next.

### Phase 0 — Foundation, contracts, and the deployment spike
- Multi-module repo, git, `.gitignore`, `README.md`, `DEVELOPER_GUIDE.md` skeleton
- `platform-common`: error model (RFC 9457 problem details), tracing, idempotency,
  `ServiceClient` SPI with in-process and HTTP implementations, money types,
  credential encryption
- `contracts/`: OpenAPI skeletons for every service, linted in CI
- `gateway` + two skeleton services, each with `/health` and Flyway
- **The composite launcher spike:** all services in one JVM, one port, browser
  opens and reaches both services through the gateway. This must work before
  anything else is built, and its smoke test runs in CI from now on.
- Distributed tracing verified end to end — one trace id across services
- Migrations proven on both H2 and PostgreSQL
- ArchUnit domain-purity test; GitHub Actions build + test on push
- **Tag `v0.1.0-foundation`**

### Phase 1 — Catalog, stock, and channel scaffolding
- `catalog-service`: Category, Item, Variant, Firm CRUD + pack manifest loading
  and validation
- `stock-service`: ledger, balances, reservations with TTL, idempotency,
  `rebuildBalances()` + invariant test
- Concurrency test: N parallel reservations against limited stock, exactly the
  right number succeed, no negative balance; plus a multi-line ordered-locking
  test proving no deadlock
- `channel-service` skeleton: `ChannelConnector` SPI, `ChannelCredential` storage,
  OAuth callback endpoint, token refresh scheduler, **stub connector** proving the
  whole flow end to end
- React shell: layout, routing, generated API client, catalog screens, Firm
  Connections tab
- **Tag `v0.2.0-catalog-stock`**

### Phase 2 — Purchases
- `purchase-service`: manual entry, weighted average costing with `cost_history`,
  stock raise via a single idempotent movement call
- Cost lookup API, consumed later by sales
- **Tag `v0.3.0-purchases`**

### Phase 3 — Sales and returns
- `sales-service`: order entry, `costAtSale` frozen from purchase-service,
  `shipmentRef` and the natural key on sale lines
- **Offline counter screen** — keyboard-first, single screen, no page reloads
- **End-of-day batch entry** — grid, paste or type many rows, post together
- Both write the same Sale through the same service; only the UI differs
- `returns-service`: both axes, restock and write-off effects, over-return guard
- **Tag `v0.4.0-sales-returns`**

### Phase 4 — File import
- `connector-file`: Excel purchase upload — parse → **preview showing matched and
  unmatched SKUs** → confirm → post, all recorded as an `ImportBatch`
- Channel sales report import through the same normalisation pipeline, with
  per-firm column mapping stored as configuration and editable in the UI, so a
  report format change is a mapping edit and not a code change
- Re-importing the same file produces zero new rows — test this explicitly
- **Ask for a real sample report before implementing any concrete column mapping.**
  Do not guess at column names.
- **Tag `v0.5.0-file-import`**

### Phase 5 — Reporting and dashboard
- Transactional outbox in the publishing services; in-process event bus
- `reporting-service` read model built from those events, with a **replay from
  scratch** capability and a test proving replay reproduces the same numbers
- Dashboard: revenue, COGS, gross margin, fees, return rate, damaged and lost
  write-off value, net P&L. Filterable by date range and firm.
- Colour × size stock grid; low stock panel; missing-cost panel
- UI shows an explicit "updating" state while the projection catches up
- Perf test against the §2 targets
- **Tag `v0.6.0-dashboard`**

### Phase 6 — Flipkart API connector
- `connector-flipkart` implementing the full `ChannelConnector` SPI
- OAuth authorisation flow per firm, real token exchange and refresh
- Order and return fetch with date-range filters; shipment-aware mapping
- Incremental sync with a per-firm watermark; safe to re-run
- Rate limiting, retry with backoff, and a clear UI state when the token expires
- **File import must remain fully functional as the fallback** — API downtime,
  expired tokens, and customers without developer access all need it
- **Tag `v0.7.0-flipkart-api`**

### Phase 7 — Packaging
- React production build served by the gateway
- `jlink` trimmed runtime + `jpackage` Windows installer (MSI/EXE)
- Desktop shortcut and Start Menu entry; launches and opens the browser once
  health checks pass
- Bound to `127.0.0.1` only, with Origin/Host validation (§10)
- **Backup and restore** of the data folder from the UI: consistent across all
  service databases (quiesce writes, snapshot, resume), stamped with the app and
  schema version, restore refuses a backup from a newer version with a clear message
- **Upgrade path proven**: installing the new version over the old one takes an
  automatic pre-migration backup, runs migrations, and rolls back to the backup if
  they fail. Test an actual N-1 → N upgrade before tagging.
- First-run setup wizard: firm details, pack selection, seed categories
- **Diagnostics bundle** — one button that zips logs, versions and schema state
  for support, with credentials and customer data excluded
- **Tag `v1.0.0`**

### Phase 8 — Toys pack
- Add `pack-toys` as a **manifest only**
- If any React change is needed to support it, the pack abstraction has failed —
  stop and fix the abstraction rather than special-casing
- **Tag `v1.1.0-toys`**

### Phase 9 — Cloud and mobile (design only for now)
- PostgreSQL adapters, containerised deployment, real message broker
- Transactional outbox pushing a **read-only** snapshot to cloud
- **One-way push only.** The desktop remains the source of truth. Do not build
  bidirectional sync — conflict resolution on stock quantities is a large project
  whose failure mode is wrong stock counts.

---

## 9. Engineering standards

**Money — get this right once, in `platform-common`**
- `BigDecimal` only, never `double`, never `float`
- **Prices, fees and totals: scale 2, `HALF_UP`.** Round only at the point of
  storage or display, never on intermediates.
- **Unit costs and weighted averages: scale 4.** Rounding average cost to 2
  decimals accumulates error across thousands of receipts and quietly corrupts
  margin reporting.
- Single currency (INR) assumed; there is no currency column. If multi-currency
  ever arrives it is a schema change — record that in the deferred-scope section.
- Provide `Money` and `Quantity` value types with the arithmetic rules baked in,
  and forbid raw `BigDecimal` in the domain via an ArchUnit rule.

**Time**
- Store every instant as UTC (`Instant` / `timestamptz`), no exceptions
- The tenant has a configured display time zone; convert only at the edges
- The **business day** is defined in tenant configuration and is what date-range
  dashboard filters use. A sale at 23:50 local must land on the day the customer
  thinks it did. Write a test that crosses a day boundary.
- Marketplace timestamps are normalised to UTC at the connector boundary

**Testing**
- Unit tests for all domain logic; domain layers need no Spring context
- Integration tests per service with Testcontainers (PostgreSQL) and H2
- **Contract tests** between services (Spring Cloud Contract or Pact) — not
  optional in a microservice system
- **The co-located launcher smoke test runs in CI on every PR**
- Must be covered: ledger invariant, concurrent reservation safety, ordered
  locking with no deadlock, idempotent replay, replay with a mismatched body,
  reservation expiry mid-flight, average cost calculation, cost-frozen-at-sale,
  missing-cost handling, both return axes, over-return rejection, import preview
  matching, re-import producing no duplicates, projection replay determinism,
  OAuth callback with a bad state parameter, credential redaction in serialisation
- Aim for meaningful domain coverage, not a coverage percentage target
- Tests are deterministic: no `Thread.sleep`, no real clock — inject a `Clock`

**Code quality**
- Constructor injection only; no field injection
- DTOs at every API boundary — never expose JPA entities
- Bean Validation on all inbound DTOs
- One global `@RestControllerAdvice` per service returning RFC 9457 problem
  details, with **stable machine-readable `type` URIs** the frontend can switch on
- No business logic in controllers
- Records for DTOs; immutable value objects where practical
- Spotless or equivalent, enforced in the build, plus a static analysis gate
  (Error Prone / SpotBugs) failing the build on new high-severity findings

**Resilience**
- Timeouts on every inter-service and outbound call — no unbounded waits
- Retry with backoff **only** on idempotent operations
- Circuit breaker (Resilience4j) on cross-service and marketplace calls
- Every failure surfaced to the user in plain language, never a stack trace; the
  message includes a support id that maps to the trace id in the logs

**Database**
- Forward-only versioned migrations; never edit an applied migration
- Every table: `id`, `tenant_id`, `created_at`, `updated_at`, `version`
- Every query filters on `tenant_id` — enforce with a Hibernate filter or an
  interceptor, not developer discipline, and write a test that a missing tenant
  filter fails
- Index anything the dashboard filters on; unique-index every natural key
- Use UUIDv7 (or another time-ordered id) for primary keys — random UUIDs
  fragment indexes and hurt exactly the range queries the dashboard runs

**Frontend**
- TypeScript strict mode
- TanStack Query for all server state; no fetch-in-useEffect
- API client generated from OpenAPI so types cannot drift; generation runs in CI
  and a stale client fails the build
- Forms driven by the pack manifest, never hardcoded fields
- **No optimistic UI for anything touching stock or money.** Show the server's
  answer. An optimistic decrement that later fails is exactly the confusion this
  architecture exists to prevent.
- Error boundaries around each route; a failed panel never blanks the app
- Keyboard accessible, visible focus states, works on a laptop screen
- No CDN or external runtime dependency — the app must work with no internet

**Observability and support**
- Structured JSON logs with trace id, span id, tenant id on every line
- Log files rotate by size and age in the data folder, capped in total size — a
  desktop app must never fill the customer's disk
- No customer data, tokens, or PII in logs
- Health endpoints distinguish liveness from readiness; the launcher waits for
  readiness before opening the browser
- **No telemetry leaves the machine** without explicit opt-in

---

## 10. Security

- Bind to `127.0.0.1` only in desktop mode
- **Validate `Origin` and `Host` headers at the gateway** and reject unexpected
  values. Binding to localhost is not a security boundary on its own — any local
  process and any web page the user visits can reach a localhost port, and DNS
  rebinding defeats naive checks.
- Parameterised queries only; no string-concatenated SQL anywhere
- Validate and size-limit all uploads; cap row counts; never trust a filename or
  a declared content type; write uploads to a temp path outside the web root and
  delete them after processing
- Spreadsheet imports: guard against formula injection on export, zip-bomb and
  XXE on import (disable external entity resolution explicitly)
- OAuth tokens encrypted at rest (AES-GCM, key outside the database, key-id
  column for rotation), never logged, never returned by any API
- Dependency vulnerability scan in CI; the build fails on new criticals
- **Authentication is deliberately absent in v1** because the app is single-user
  and localhost-bound. This is a decision, not an oversight: record it as ADR and
  as a known issue, and leave the seam — a `tenant_id` on every table and a single
  place where the current principal is resolved — so adding auth in the cloud
  phase is not a rewrite.

---

## 11. `docs/DEVELOPER_GUIDE.md` — required, kept current

This is the primary handover document and the main artefact of this project
besides the code. Assume a developer joins with zero context and must ship a
feature. **Create it in Phase 0 and update it at the end of every phase** — a
phase is not complete until the guide reflects it.

It must contain:

1. **What this product is**, who it is for, and the business model (single-PC
   install today, multi-customer product later)
2. **Feature catalogue** — every feature, its current state
   (`built` / `in progress` / `planned` / `deferred`), and which phase it belongs to
3. **Service map** — what each service owns, what it must never own, and the call
   graph between services
4. **Architectural decisions and the reasoning**, including rejected alternatives
   and why they were rejected: modular monolith, sagas/compensating transactions
   for stock, EAV attribute storage, SQLite as the local engine, bidirectional
   cloud sync, Docker-based desktop packaging, authentication in v1
5. **The single-writer stock rule**, the locking protocol and the reservation
   protocol, described in enough detail to implement against
6. **Domain model per service**, with the invariants that must hold: ledger is the
   source of truth and append-only, cost frozen at sale, two independent return
   axes, natural keys that make imports idempotent
7. **The channel pipeline** — how file import and API fetch converge, and how to
   add a new connector
8. **How to add a new vertical pack** — a step-by-step worked example
9. **How to add a new service** — checklist covering contracts, tracing,
   migrations, launcher registration, contract tests, tenant filtering
10. **Local setup** — prerequisites, running co-located and distributed, switching
    storage engine, resetting databases, running the stub connector
11. **Build and release** — producing the installer, tagging a release, and the
    **upgrade and rollback procedure** for an existing customer install
12. **Testing strategy** — what is tested where, how to run it
13. **Operations and support** — where data and logs live, how to take and restore
    a backup, how to produce a diagnostics bundle, what to do when a token expires
14. **Known issues and technical debt** — a live, numbered register. Every entry:
    what it is, why it exists, what it blocks, and the intended fix. Add to it
    whenever you take a shortcut, hit a limitation, or defer something. **Do not
    silently work around a problem** — record it here.
15. **Open questions** — mirrored from §14, kept current
16. **Deferred scope and the seam left for each** — tax/GST, licensing,
    authentication, multi-currency, pharma vertical, cloud sync, mobile app

Also maintain `docs/adr/` — one short Architecture Decision Record per significant
decision: context, options considered, decision, consequences.

---

## 12. Git and versioning

- Initialise the repo in Phase 0; first commit before any feature code
- **Trunk-based with short-lived branches**: `feature/<phase>-<short-name>`,
  merged via PR into `main`
- **Conventional Commits**: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`
- Annotated tag at the end of each phase, as listed above
- Semantic versioning; `CHANGELOG.md` updated per phase
- `.gitignore` excludes `data/`, `*.db`, `target/`, `node_modules/`, `dist/`, and
  any local config containing secrets or tokens
- **Never commit** the data folder, real customer data, API credentials, or real
  marketplace reports. Add a secret-scanning pre-commit hook — the OAuth work in
  Phase 1 makes an accidental token commit a realistic risk.
- GitHub Actions on every PR: build, unit tests, contract tests, ArchUnit checks,
  launcher smoke test, OpenAPI lint and breaking-change check, dependency scan

---

## 13. How to work with me

1. Start with **Phase 0 only**. Show me the service skeleton, the composite
   launcher spike, and `DEVELOPER_GUIDE.md` before writing feature code.
2. At the start of each phase, give me a short plan and wait for confirmation.
3. When a requirement is ambiguous, **ask**. Do not invent business rules,
   especially around money or stock quantities. §14 is the running list.
4. **Do everything that is not blocked.** If one open question blocks one part of
   a phase, build the rest, state the assumption you would make, and flag it —
   do not stall the whole phase on one answer.
5. If you find yourself writing a saga or compensating transaction for stock,
   **stop and flag it** — the design has drifted.
6. If you find yourself special-casing "clothes" outside `pack-apparel`, or
   "Flipkart" outside `connector-flipkart`, stop and flag it. That is a design
   failure, not a shortcut.
7. If you think something in this document is wrong, say so. It is a brief, not
   scripture — but argue it before you build against it, not after.
8. Log every assumption and every shortcut in the known-issues register in the
   developer guide, and list them at the end of each phase.
9. **Report honestly.** If a test fails, show the output. If you skipped
   something, say which and why. Never report a phase complete on the basis of
   code that has not been run.

---

## 14. Open questions — answer before the phase noted, do not guess

Keep this list live. Add new entries as they surface; mark answered ones with the
answer and the date.

| # | Question | Blocks | Status |
|---|---|---|---|
| 14.1 | How are purchase extra costs (freight, packing) allocated across lines — by value, by quantity, by weight, or not at all? This changes every margin number. | Phase 2 | **Open** |
| 14.2 | Are marketplace fees reversed on a return? Does it differ between courier and customer returns? | Phase 3 | **Open** |
| 14.3 | Does Flipkart's developer portal accept a `127.0.0.1` OAuth callback? If not, a small hosted callback endpoint is needed, pulling cloud infrastructure forward from Phase 9. | Phase 6 | **Open** |
| 14.4 | Real sample files: a supplier purchase Excel and a Flipkart sales report per firm. Needed before any concrete column mapping is written. | Phase 4 | **Open** |
| 14.5 | Accounting treatment of courier vs customer returns — what actually differs downstream? The distinction is preserved in the model regardless. | Phase 5 | **Open** |
| 14.6 | Opening stock: how is day-one inventory entered, and at what cost? Adjustment-with-cost, or a synthetic opening purchase? This determines whether `costAtSale` can be missing at all. | Phase 2 | **Open** |
| 14.7 | Is a sale ever edited or cancelled after posting, and if so does it reverse stock or become a return? | Phase 3 | **Open** |

---

## 15. Explicitly out of scope — do not build

- **Tax / GST of any kind.** No tax fields, no HSN codes, no tax rates, no
  CGST/SGST/IGST, no GSTR export. The rules are unsettled and will be specified
  later as a separate module. Do not add speculative tax columns "just in case" —
  half-specified fields nobody understands are worse than none. The one thing to
  preserve is the courier-versus-customer return distinction, which is already in
  the domain model for its own reasons.
- **Authentication and user accounts** — single-user localhost app for now; leave
  the seam described in §10
- **Multi-currency** — single currency, no currency column
- **Licensing / entitlement enforcement** — config property only for now
- **Pharma vertical** — leave the capability flags (`batchTracking`,
  `expiryTracking`) in the manifest model, implement nothing
- **Cloud sync and mobile app** — Phase 9, design only
- **`connector-flipkart`** before Phase 6 — the SPI and OAuth frame come first,
  the concrete implementation waits for real credentials

---

Begin with Phase 0. Before you write code, tell me: the current stable versions
you found for the pinned dependencies (§0), whether you accept the two locked-
decision changes in §5, and which of the §14 open questions block Phase 0.
