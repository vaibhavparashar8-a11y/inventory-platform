package com.inventoryplatform.common.idempotency;

import java.time.Instant;

import com.inventoryplatform.common.tenant.TenantId;

/**
 * The stored outcome of one idempotent operation.
 *
 * <p>The {@code requestFingerprint} is what makes replay safe rather than merely quiet: replaying a
 * key with a <em>different</em> body is a caller bug, and returning the first response would hide
 * it. The stored {@code responseBody} and {@code status} are returned verbatim on replay so a retry
 * is indistinguishable from the original call.
 *
 * @param key the caller-supplied key
 * @param tenantId scope, so two tenants cannot collide on a key
 * @param endpoint scope, so the same key on a different operation is a different record
 * @param requestFingerprint hash of the request body
 * @param state IN_FLIGHT while running, COMPLETED once a response exists
 * @param responseStatus HTTP status of the original response, null while in flight
 * @param responseBody serialised original response, null while in flight
 * @param createdAt used by the 7-day purge job
 */
public record IdempotencyRecord(
        IdempotencyKey key,
        TenantId tenantId,
        String endpoint,
        String requestFingerprint,
        State state,
        Integer responseStatus,
        String responseBody,
        Instant createdAt) {

    public enum State {
        /** A request with this key is running now. A second one must not proceed. */
        IN_FLIGHT,
        /** The original completed; its response is stored and replayable. */
        COMPLETED
    }

    public IdempotencyRecord completedWith(int status, String body) {
        return new IdempotencyRecord(
                key, tenantId, endpoint, requestFingerprint, State.COMPLETED, status, body, createdAt);
    }

    public boolean isInFlight() {
        return state == State.IN_FLIGHT;
    }

    public boolean matches(String otherFingerprint) {
        return requestFingerprint.equals(otherFingerprint);
    }
}
