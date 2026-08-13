package com.inventoryplatform.common.idempotency;

import java.io.Serial;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;

import com.inventoryplatform.common.error.PlatformException;
import com.inventoryplatform.common.error.ProblemTypes;
import com.inventoryplatform.common.tenant.TenantId;

import tools.jackson.databind.ObjectMapper;

/**
 * Runs an operation at most once per idempotency key, implementing the semantics in
 * BUILD_PROMPT.md §3.
 *
 * <ul>
 *   <li>first call — runs, stores the response
 *   <li>replay with the same body — returns the stored response verbatim, does not re-run
 *   <li>concurrent duplicate still in flight — 409, does not block and does not duplicate
 *   <li>same key, different body — 422, because that is a caller bug and must be loud
 * </ul>
 *
 * <p>Blocking on an in-flight duplicate was rejected: it converts a client's double-click into a
 * held request thread, and under the desktop's single JVM that is how you deadlock a shop counter
 * during a sale.
 */
public final class IdempotentOperation {

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotentOperation(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /**
     * @param key caller-supplied idempotency key
     * @param endpoint logical operation name, part of the uniqueness scope
     * @param request the request payload, hashed to detect key reuse with a different body
     * @param responseType type to deserialise a stored response back into on replay
     * @param work the operation itself; runs only if this caller claims the key
     */
    public <T> T execute(
            IdempotencyKey key,
            TenantId tenantId,
            String endpoint,
            Object request,
            Class<T> responseType,
            Supplier<T> work) {

        String fingerprint = fingerprint(request);
        Optional<IdempotencyRecord> existing = store.claim(key, tenantId, endpoint, fingerprint);

        if (existing.isPresent()) {
            return replay(existing.get(), key, fingerprint, responseType);
        }

        try {
            T result = work.get();
            store.complete(key, tenantId, endpoint, HttpStatus.OK.value(), serialise(result));
            return result;
        } catch (RuntimeException e) {
            // The claim must not outlive a failed attempt, or an honest retry is locked out
            // forever by a record that will never complete.
            store.release(key, tenantId, endpoint);
            throw e;
        }
    }

    private <T> T replay(
            IdempotencyRecord record, IdempotencyKey key, String fingerprint, Class<T> responseType) {

        if (!record.matches(fingerprint)) {
            throw new KeyReused(key);
        }
        if (record.isInFlight()) {
            throw new InFlight(key);
        }
        return deserialise(record.responseBody(), responseType);
    }

    private String fingerprint(Object request) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("Request payload could not be fingerprinted", e);
        }
    }

    private String serialise(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Response could not be stored for idempotent replay", e);
        }
    }

    private <T> T deserialise(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException("Stored idempotent response could not be replayed", e);
        }
    }

    /** A request with this key is running right now. */
    public static final class InFlight extends PlatformException {
        @Serial private static final long serialVersionUID = 1L;

        public InFlight(IdempotencyKey key) {
            super(
                    ProblemTypes.IDEMPOTENCY_IN_FLIGHT,
                    HttpStatus.CONFLICT,
                    "This request is already being processed. Please wait a moment before retrying.",
                    Map.of("idempotencyKey", key.value()));
        }
    }

    /** Same key, different body — the caller has reused a key it should not have. */
    public static final class KeyReused extends PlatformException {
        @Serial private static final long serialVersionUID = 1L;

        public KeyReused(IdempotencyKey key) {
            super(
                    ProblemTypes.IDEMPOTENCY_KEY_REUSED,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "This idempotency key was already used for a different request.",
                    Map.of("idempotencyKey", key.value()));
        }
    }
}
