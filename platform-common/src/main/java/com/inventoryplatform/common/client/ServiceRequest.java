package com.inventoryplatform.common.client;

import java.util.Objects;
import java.util.Optional;

import com.inventoryplatform.common.idempotency.IdempotencyKey;

/**
 * One call from one service to another, expressed independently of transport.
 *
 * <p>The {@code operation} is a logical name ({@code stock.reserve}), not a URL path. Both bindings
 * resolve it themselves — the HTTP one to a route, the in-process one to a registered handler — so
 * that calling code contains no transport detail and behaves identically in both deployment shapes.
 *
 * @param targetService the service being called, e.g. {@code stock-service}
 * @param operation logical operation name, e.g. {@code stock.reserve}
 * @param payload request body; serialised even in-process, so callee and caller never share objects
 * @param responseType type the response is deserialised into
 * @param idempotencyKey required for state-changing calls, absent for reads
 */
public record ServiceRequest<R>(
        String targetService,
        String operation,
        Object payload,
        Class<R> responseType,
        Optional<IdempotencyKey> idempotencyKey) {

    public ServiceRequest {
        Objects.requireNonNull(targetService, "targetService");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(responseType, "responseType");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    /** A read: no idempotency key, because nothing changes. */
    public static <R> ServiceRequest<R> query(
            String targetService, String operation, Object payload, Class<R> responseType) {
        return new ServiceRequest<>(targetService, operation, payload, responseType, Optional.empty());
    }

    /** A state change: an idempotency key is mandatory (BUILD_PROMPT.md §3). */
    public static <R> ServiceRequest<R> command(
            String targetService,
            String operation,
            Object payload,
            Class<R> responseType,
            IdempotencyKey idempotencyKey) {
        return new ServiceRequest<>(
                targetService,
                operation,
                payload,
                responseType,
                Optional.of(Objects.requireNonNull(idempotencyKey, "idempotencyKey")));
    }
}
