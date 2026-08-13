package com.inventoryplatform.common.error;

import java.net.URI;

/**
 * Stable, machine-readable problem type URIs (RFC 9457).
 *
 * <p>The frontend switches on these, so they are part of the API contract: once shipped, a value
 * here may be added to but never renamed or repurposed. The human-readable title may change freely;
 * the URI may not.
 *
 * <p>They are URNs rather than resolvable URLs deliberately — the app runs offline on a desktop, so
 * a type URI that looks dereferenceable would be a broken promise.
 */
public final class ProblemTypes {

    private static final String PREFIX = "urn:problem:";

    /** A referenced entity does not exist, or is not visible to this tenant. */
    public static final URI NOT_FOUND = URI.create(PREFIX + "not-found");

    /** Inbound payload failed validation. Carries field-level errors. */
    public static final URI VALIDATION_FAILED = URI.create(PREFIX + "validation-failed");

    /** The request conflicts with current state (optimistic lock, duplicate natural key). */
    public static final URI CONFLICT = URI.create(PREFIX + "conflict");

    /** Not enough available stock. Distinct from CONFLICT so the UI can offer a real remedy. */
    public static final URI INSUFFICIENT_STOCK = URI.create(PREFIX + "insufficient-stock");

    /** Commit attempted against a reservation that expired or was already released. */
    public static final URI RESERVATION_EXPIRED = URI.create(PREFIX + "reservation-expired");

    /** A request with this idempotency key is still in flight. */
    public static final URI IDEMPOTENCY_IN_FLIGHT = URI.create(PREFIX + "idempotency-in-flight");

    /** Same idempotency key replayed with a different body — a caller bug, and loud on purpose. */
    public static final URI IDEMPOTENCY_KEY_REUSED = URI.create(PREFIX + "idempotency-key-reused");

    /** A downstream service was unreachable, timed out, or tripped a circuit breaker. */
    public static final URI SERVICE_UNAVAILABLE = URI.create(PREFIX + "service-unavailable");

    /** Anything unhandled. The detail is deliberately generic; the support id carries the rest. */
    public static final URI INTERNAL_ERROR = URI.create(PREFIX + "internal-error");

    private ProblemTypes() {}
}
