package com.inventoryplatform.common.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.inventoryplatform.common.tenant.TenantId;

/**
 * In-memory {@link IdempotencyStore} for tests, published in the test-jar so every service can
 * exercise idempotent behaviour without a database.
 *
 * <p>{@link ConcurrentHashMap#putIfAbsent} stands in for the unique constraint a real
 * implementation relies on — atomic in the same way, which is what the concurrency tests need.
 *
 * <p>Not for production: it does not survive a restart, and in cloud mode it would not be shared
 * between replicas.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<IdempotencyRecord> claim(
            IdempotencyKey key, TenantId tenantId, String endpoint, String requestFingerprint) {

        IdempotencyRecord candidate =
                new IdempotencyRecord(
                        key,
                        tenantId,
                        endpoint,
                        requestFingerprint,
                        IdempotencyRecord.State.IN_FLIGHT,
                        null,
                        null,
                        clock.instant());

        return Optional.ofNullable(records.putIfAbsent(id(key, tenantId, endpoint), candidate));
    }

    @Override
    public void complete(
            IdempotencyKey key, TenantId tenantId, String endpoint, int status, String body) {
        records.computeIfPresent(
                id(key, tenantId, endpoint), (k, existing) -> existing.completedWith(status, body));
    }

    @Override
    public void release(IdempotencyKey key, TenantId tenantId, String endpoint) {
        records.remove(id(key, tenantId, endpoint));
    }

    @Override
    public Optional<IdempotencyRecord> find(IdempotencyKey key, TenantId tenantId, String endpoint) {
        return Optional.ofNullable(records.get(id(key, tenantId, endpoint)));
    }

    @Override
    public int purgeOlderThan(Instant cutoff) {
        int before = records.size();
        records.values().removeIf(record -> record.createdAt().isBefore(cutoff));
        return before - records.size();
    }

    private String id(IdempotencyKey key, TenantId tenantId, String endpoint) {
        return tenantId.value() + '|' + endpoint + '|' + key.value();
    }
}
