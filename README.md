# inventory-platform

A local-first inventory, sales and profitability platform for small retailers.

It runs as **one process on one Windows PC** for the shopkeeper who uses it, and
as **eight services** in the cloud — from a single codebase. Stock is a shared
pool across multiple selling identities (marketplace seller accounts and an
offline counter), and the ledger is the source of truth.

> **Status: Phase 0 — foundation.** Not yet usable as a product.
> See [`BUILD_PROMPT.md`](BUILD_PROMPT.md) for the full brief and phase plan,
> and [`docs/DEVELOPER_GUIDE.md`](docs/DEVELOPER_GUIDE.md) to get productive.

## Quick start

Requires **JDK 21** and **Node 24**. Maven is supplied by the committed wrapper —
do not use a global `mvn`.

```bash
./mvnw verify          # build, test, ArchUnit, formatting gate
./mvnw -pl launcher spring-boot:run   # desktop mode: all services, one JVM, one port
```

Then open <http://127.0.0.1:8080>.

## Why it is built this way

Three decisions explain most of the codebase:

- **`stock-service` is the sole writer of stock quantity.** "Stock never goes
  negative" must hold at every instant, not eventually — so the ledger lives in
  one service where one local transaction enforces it. There are no sagas and no
  compensating transactions for stock, by design.
- **Deployment differs; architecture does not.** The desktop customer cannot run
  eight JVMs, so all services are co-located in a single JVM behind an in-process
  transport. Service code is identical in both shapes.
- **Verticals and sales channels are pluggable.** Apparel is a manifest, not
  hardcoded logic. Marketplace file import and API fetch converge on one
  normalisation pipeline.

The reasoning, including the alternatives that were rejected and why, is in
[`docs/adr/`](docs/adr/).

## Repository layout

| Path | Contains |
|---|---|
| `contracts/` | OpenAPI specs — written before implementations |
| `platform-common/` | tracing, error model, idempotency, money, `ServiceClient` SPI |
| `services/` | the eight services |
| `packs/` | vertical packs (apparel, later toys) |
| `connectors/` | channel connectors (file, later Flipkart) |
| `launcher/` | composite single-JVM launcher for desktop mode |
| `web-ui/` | React app |
| `packaging/` | jlink/jpackage Windows installer |
| `docs/` | developer guide, ADRs |

## Contributing

Read [`CLAUDE.md`](CLAUDE.md) first — it lists the rules that are not negotiable
and the mistakes that are easy to make here.

This repository is **public**. Never commit customer data, real marketplace
reports, credentials or tokens. A secret-scanning pre-commit hook is enabled via
`git config core.hooksPath .githooks`.

## Licence

Not yet determined — this is a commercial product. Treat as all rights reserved
until a licence file is added.
