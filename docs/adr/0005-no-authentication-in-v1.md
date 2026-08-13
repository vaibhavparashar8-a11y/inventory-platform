# ADR 0005 — No authentication in v1

**Status:** accepted, 2026-08-13

## Context

The application is single-user and bound to `127.0.0.1`. Adding user accounts,
sessions and password management now would be substantial work with no
user-visible benefit for the first customer.

## Decision

No authentication in v1. This is recorded as a decision precisely so that nobody
later reads the absence as an oversight and either panics or quietly ships it to
a multi-tenant deployment.

## Consequences

- `tenant_id` exists on every table from the first migration, and `TenantContext`
  is the single place a principal is resolved. Adding auth later is wiring, not a
  rewrite.
- **Localhost is not a security boundary.** Any local process, and any web page
  the user visits, can reach a localhost port; DNS rebinding defeats naive host
  checks. The gateway therefore validates `Origin` and `Host` — that work is not
  optional just because auth is absent.
- This must be revisited before the cloud phase. A multi-tenant deployment
  without authentication is not shippable, and Phase 9 should not be started
  until it is scheduled.
