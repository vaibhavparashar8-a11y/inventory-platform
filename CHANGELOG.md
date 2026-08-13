# Changelog

Notable changes per phase. Semantic versioning; each phase ends in an annotated
tag.

## [Unreleased] — Phase 0, foundation

### Added
- Multi-module Maven build on Java 21 / Spring Boot 4.1.0, every version pinned
  and verified against Maven Central; Spotless and Enforcer wired as build gates
  rather than advice
- `platform-common`: money value types, RFC 9457 error model, tenant context,
  idempotency, `ServiceClient` SPI with in-process and HTTP bindings, AES-GCM
  credential encryption with key rotation, shared ArchUnit rules
- OpenAPI contracts for all eight services, Spectral-linted in CI
- `catalog-service` and `stock-service` skeletons with Flyway migrations and a
  genuine liveness/readiness split
- CI on every push: build, test, ArchUnit, formatting, contract lint, plus CodeQL
  and Dependabot
- Secret-scanning pre-commit hook, verified against a planted token and an
  env-var reference
- `docs/DEVELOPER_GUIDE.md` and five ADRs

### Changed from the original brief
- Java 17 → 21 and Spring Boot 3.3 → 4.1 (ADR 0001)
- SQLite → H2 file mode as the local engine (ADR 0002), because SQLite cannot
  provide the row-level locking the stock invariant depends on
- Purchases post a single idempotent movement instead of reserve-then-commit: a
  stock increase cannot violate the invariant, so the reservation was ceremony

### Fixed
- `mvnw` executable bit missing on Windows checkouts, which broke Linux CI
- CRLF line endings failing the formatting gate; normalised via `.gitattributes`
- Migration portability: `CLOB` does not exist in PostgreSQL; bare `TIMESTAMP`
  contradicted the store-in-UTC rule
- `HttpServiceClient` threw a raw Spring exception when a downstream returned a
  non-JSON error body, instead of the expected `ServiceUnavailable`
