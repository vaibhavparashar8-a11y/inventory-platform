package com.inventoryplatform.common.client;

/**
 * The seam that lets one codebase deploy as one process or as eight.
 *
 * <p>Two implementations exist and service code must not know which it has:
 *
 * <ul>
 *   <li>{@code InProcessServiceClient} — desktop mode, all services in one JVM
 *   <li>{@code HttpServiceClient} — cloud mode, real HTTP between containers
 * </ul>
 *
 * <p>The in-process binding is deliberately <em>not</em> a shortcut. It serialises payloads,
 * propagates the trace context and idempotency key, applies timeouts, and starts a new transaction
 * in the callee. If it took the fast path instead, the desktop build would have different
 * consistency semantics from cloud and would hide the bugs that only surface there — which defeats
 * the point of having one codebase (BUILD_PROMPT.md §3).
 */
public interface ServiceClient {

    /**
     * @throws com.inventoryplatform.common.error.PlatformExceptions.ServiceUnavailable if the target
     *     cannot be reached, times out, or has no handler registered
     */
    <R> R call(ServiceRequest<R> request);
}
