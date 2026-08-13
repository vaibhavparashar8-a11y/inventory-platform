-- stock-service baseline.
--
-- Portable SQL only: H2 is the local engine and PostgreSQL the cloud one, and CI
-- runs this against both. Anything engine-specific here would mean the desktop
-- build and the cloud build diverge at the schema, which is the one place a
-- divergence is most expensive to discover late.
--
-- Every table carries tenant_id from the first migration (BUILD_PROMPT.md §5).
-- There is one tenant per desktop install today; adding the column later, to a
-- live customer database, and backfilling every row and index correctly, is a
-- migration nobody wants to run on a shopkeeper's PC.

-- ---------------------------------------------------------------------------
-- Idempotency records. Not business data, but it must be transactional with the
-- work it guards, so it lives in the service's own database rather than a shared
-- store (BUILD_PROMPT.md §3).
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_record (
    id                  VARCHAR(36)   NOT NULL,
    tenant_id           VARCHAR(64)   NOT NULL,
    idempotency_key     VARCHAR(128)  NOT NULL,
    endpoint            VARCHAR(128)  NOT NULL,
    request_fingerprint VARCHAR(64)   NOT NULL,
    state               VARCHAR(16)   NOT NULL,
    response_status     INTEGER,
    -- VARCHAR rather than CLOB/TEXT: CLOB does not exist in PostgreSQL and TEXT is
    -- not dependable across both engines. Stored responses are small JSON documents.
    response_body       VARCHAR(8000),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (id)
);

-- The uniqueness guarantee must come from the database, not application code:
-- two concurrent requests with the same key race, and exactly one must win.
CREATE UNIQUE INDEX ux_idempotency_key
    ON idempotency_record (tenant_id, endpoint, idempotency_key);

-- Supports the 7-day purge job.
CREATE INDEX ix_idempotency_created_at
    ON idempotency_record (created_at);
