package com.inventoryplatform.common.client;

import java.util.Map;
import java.util.Objects;

/**
 * Where each service lives, and how a logical operation becomes a URL.
 *
 * <p>Only the HTTP binding needs this; in desktop mode the map is empty because nothing leaves the
 * JVM. Keeping route knowledge here rather than in calling code is what lets the same call site work
 * in both deployment shapes.
 *
 * <p>Paths follow one convention — {@code POST {baseUrl}/internal/{operation}} — rather than being
 * configured per operation. These are service-to-service calls, not the public API in
 * {@code contracts/}: inventing REST semantics for them would add ceremony without adding meaning.
 */
public final class ServiceRoutes {

    private final Map<String, String> baseUrls;

    public ServiceRoutes(Map<String, String> baseUrls) {
        this.baseUrls = Map.copyOf(Objects.requireNonNull(baseUrls, "baseUrls"));
    }

    /** Desktop mode: nothing is reachable over HTTP because nothing needs to be. */
    public static ServiceRoutes none() {
        return new ServiceRoutes(Map.of());
    }

    /**
     * @throws IllegalStateException if the service has no configured base URL — a misconfigured
     *     deployment should fail loudly at the first call, not quietly resolve to localhost
     */
    public String urlFor(String targetService, String operation) {
        String baseUrl = baseUrls.get(targetService);
        if (baseUrl == null) {
            throw new IllegalStateException(
                    "No base URL is configured for '" + targetService + "'");
        }
        return baseUrl + "/internal/" + operation;
    }

    public boolean isEmpty() {
        return baseUrls.isEmpty();
    }
}
