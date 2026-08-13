package com.inventoryplatform.common.tenant;

import java.util.Optional;

/**
 * The tenant of the request being handled on this thread.
 *
 * <p>This is the single place the current principal's tenant is resolved — the seam BUILD_PROMPT.md
 * §10 requires so that adding real authentication later is not a rewrite. When auth arrives, it
 * populates this and nothing else changes.
 *
 * <p>Backed by a plain {@link ThreadLocal}, so the binding does <em>not</em> propagate to threads
 * spawned inside a request — work handed to an executor must re-bind explicitly. Callers must use
 * the scoped {@link #runWith} form rather than setting and forgetting: a tenant left behind on a
 * pooled thread is a cross-tenant data leak, which is the worst bug this system could have.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    /** The current tenant, or empty outside a request (a scheduler, a startup task). */
    public static Optional<TenantId> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * @throws IllegalStateException if no tenant is bound — callers that need one must not guess
     */
    public static TenantId require() {
        TenantId tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant bound to this thread. Wrap the work in TenantContext.runWith(...).");
        }
        return tenantId;
    }

    /** Runs {@code action} with {@code tenantId} bound, restoring the previous value afterwards. */
    public static void runWith(TenantId tenantId, Runnable action) {
        callWith(
                tenantId,
                () -> {
                    action.run();
                    return null;
                });
    }

    /** Value-returning form of {@link #runWith}. */
    public static <T> T callWith(TenantId tenantId, java.util.function.Supplier<T> action) {
        TenantId previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return action.get();
        } finally {
            // Restore rather than clear: nested calls (an in-process service call inside a
            // request) must not strip the outer tenant on the way out.
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
