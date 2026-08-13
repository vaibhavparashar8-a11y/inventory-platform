# ADR 0004 — The in-process transport imitates the network

**Status:** accepted, 2026-08-13

## Context

The desktop customer cannot run eight JVMs, so all services are co-located behind
`InProcessServiceClient`. The obvious implementation is a direct method call: it
is faster, simpler, and looks like the whole point of co-location.

## Decision

It is deliberately not a direct call. The in-process binding serialises payloads,
propagates trace context and the idempotency key, applies timeouts, and starts a
**new transaction** in the callee rather than joining the caller's.

## Consequences

- A callee cannot mutate the caller's object, because it never receives it. That
  is impossible over HTTP, so it must stay impossible in-process — otherwise
  desktop code could come to depend on shared state that cloud would break.
- A downstream failure does not roll back the caller's transaction. This is the
  property the reservation protocol depends on: the caller commits its own
  business record in its own transaction, independently of the ledger write.
- Desktop mode cannot hide consistency bugs that would only appear in cloud,
  which is the entire justification for one codebase in two shapes.
- Costs serialisation overhead on every internal call. Accepted: correctness
  parity between deployment modes is worth more than microseconds on a
  single-user desktop.
- Asserted by tests rather than documented and hoped for — a `RecordingTransaction
  Manager` checks the propagation behaviour, and a mutation test checks detachment.
