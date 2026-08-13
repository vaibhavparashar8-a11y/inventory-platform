package com.inventoryplatform.common.idempotency;

import java.time.Instant;
import java.util.Optional;

import com.inventoryplatform.common.tenant.TenantId;

/**
 * Storage for idempotency records, implemented per service against its own database.
 *
 * <p>Deliberately an SPI: the uniqueness guarantee must come from a database constraint on
 * {@code (tenant_id, endpoint, key)}, not from application code. Two concurrent requests carrying
 * the same key race here, and exactly one must win — which is a job for the database, the same
 * argument that puts the stock ledger in one service.
 */
public interface IdempotencyStore {

    /**
     * Atomically claims the key, or reports the record that already holds it.
     *
     * <p>Implementations must perform an insert that fails on the unique constraint rather than a
     * check-then-insert, which would let both racers through.
     *
     * @return empty if this caller claimed the key and should proceed; otherwise the existing record
     */
    Optional<IdempotencyRecord> claim(
            IdempotencyKey key, TenantId tenantId, String endpoint, String requestFingerprint);

    /** Stores the response so later replays return it verbatim. */
    void complete(IdempotencyKey key, TenantId tenantId, String endpoint, int status, String body);

    /**
     * Releases a claim whose operation failed, so a retry is not permanently locked out by a record
     * that will never complete.
     */
    void release(IdempotencyKey key, TenantId tenantId, String endpoint);

    Optional<IdempotencyRecord> find(IdempotencyKey key, TenantId tenantId, String endpoint);

    /**
     * Deletes records created before {@code cutoff}. Called by a scheduled job; retention is 7 days
     * (BUILD_PROMPT.md §3).
     *
     * @return how many records were removed
     */
    int purgeOlderThan(Instant cutoff);
}
