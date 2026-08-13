# ADR 0001 — Java 21 and Spring Boot 4.1

**Status:** accepted, 2026-08-13

## Context

`BUILD_PROMPT.md` originally pinned Java 17 and Spring Boot 3.3.x. Both were
chosen before the current releases existed. This is a commercial product with a
multi-year horizon, so the starting line matters more than usual: a framework
major upgrade landing mid-Phase-5 would be expensive and badly timed.

At the time of writing, Maven Central had Spring Boot 4.1.0 as current and 3.5.16
as the last of the 3.x line.

## Options

1. **Java 17 + Boot 3.5.16** — maximum library maturity and the largest body of
   examples. But OSS patch support for 3.x is near or past its end, and a major
   upgrade would have to be scheduled into a later phase.
2. **Java 21 + Boot 4.1.0** — current LTS and current framework line. Virtual
   threads matter for a gateway fanning out to eight in-process services, and
   pattern matching suits an enum-heavy domain. Risk: ecosystem lag.
3. **Spike both** — roughly doubles Phase 0 for an answer a dependency check
   gives in minutes.

## Decision

Option 2, taken only after verifying the ecosystem rather than assuming it. The
full dependency set resolved together against the Boot 4.1.0 BOM, and
Resilience4j turned out to ship a dedicated `-spring-boot4` artifact.

## Consequences

- Four migration traps found and documented in DEVELOPER_GUIDE §12. The worst by
  a distance: auto-configuration is split per technology, so `flyway-core` alone
  leaves migrations silently not running — clean startup, empty schema.
- Falling back to 3.5.x would have been cheap at Phase 0 and expensive later,
  which is exactly why the check happened before any code was written.
