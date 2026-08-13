# ADR 0002 — H2 file mode as the local engine, not SQLite

**Status:** accepted, 2026-08-13

## Context

`BUILD_PROMPT.md` originally specified SQLite per service for local storage. It
also specifies that "stock never goes negative" must hold at every instant, with
two concurrent sales serialised inside one local transaction.

Those two requirements are in direct conflict.

## Options

1. **SQLite** — smallest footprint, no server process. But it has no row-level
   locking and no `SELECT … FOR UPDATE`; it serialises at the whole-database
   level, has no first-party Hibernate dialect, and "database per service" would
   become eight single-writer files contending under exactly the concurrent-sale
   scenario the architecture exists to handle.
2. **H2 file mode** — real row locking, a supported dialect, and PostgreSQL
   compatibility mode. Larger footprint, embedded in the JVM.

## Decision

H2 file mode as the local default, PostgreSQL in cloud, and the same portable
migrations running on both.

## Consequences

- The locking protocol in DEVELOPER_GUIDE §5 becomes implementable as written.
  Under SQLite it would have been aspirational.
- Local and cloud engines behave alike, so desktop testing is meaningful evidence
  about cloud behaviour.
- Migrations must be portable SQL. Two violations were already caught before they
  shipped: `CLOB` does not exist in PostgreSQL, and bare `TIMESTAMP` contradicts
  the store-everything-in-UTC rule.
- CI must prove migrations against real PostgreSQL. Docker is unavailable on the
  dev machine and H2 alone cannot prove portability, so CI is not a convenience
  here — it is the only available proof.
