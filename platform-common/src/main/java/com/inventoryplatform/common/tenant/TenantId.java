package com.inventoryplatform.common.tenant;

import java.util.Objects;

/**
 * Identifies the customer install whose data a request touches.
 *
 * <p>Today there is exactly one tenant per desktop install, so this always holds {@link #DEFAULT}.
 * It exists from the first migration anyway (BUILD_PROMPT.md §5) because adding a tenant column to
 * a live customer database later — and backfilling every row, index and query correctly — is a
 * migration nobody wants to run on a shopkeeper's PC.
 *
 * @param value the tenant identifier, never blank
 */
public record TenantId(String value) {

    /** The single tenant of a desktop install. */
    public static final TenantId DEFAULT = new TenantId("default");

    public TenantId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Tenant id must not be blank");
        }
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
