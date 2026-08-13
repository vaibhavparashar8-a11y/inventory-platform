# ADR 0003 — One service owns stock quantity; no sagas

**Status:** accepted (carried from the brief), 2026-08-13

## Context

Stock is a single pool drawn on by several selling channels at once — two
marketplace accounts and an offline counter. The invariant "stock never goes
negative" must hold at every instant, not eventually.

## Options

1. **Each service holds its own quantity, reconciled by events** — the reflexive
   microservice answer. Requires compensating transactions, and its failure mode
   is a wrong stock count discovered days later by a non-technical shopkeeper.
2. **Sagas around a distributed stock update** — provisional state that must be
   undone. An interrupted saga leaves quantity in limbo, and someone has to
   resolve it.
3. **One service is the sole writer**, others call it synchronously.

## Decision

Option 3. `stock-service` alone has a stock table, a quantity column, or any way
to change on-hand. Multi-step flows use two-phase reservations with a TTL, never
sagas.

## Consequences

- The check and the write are one local transaction, so a rejection happens
  before anything is committed anywhere. There is no provisional state and
  therefore nothing to compensate.
- An orphaned reservation self-heals on expiry, so there is **no manual
  reconciliation screen** — non-negotiable for a customer with no IT support.
- `stock-service` becomes a hot path and a single point of failure. Accepted: in
  desktop mode it is in-process, and in cloud it is the one service worth scaling
  carefully.
- Writing a saga or compensating transaction for stock is a signal the design has
  drifted. Stop and flag it rather than working around it.
