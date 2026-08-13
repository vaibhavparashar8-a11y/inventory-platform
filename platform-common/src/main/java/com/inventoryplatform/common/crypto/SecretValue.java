package com.inventoryplatform.common.crypto;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A value that must never be logged, traced, or returned by an API.
 *
 * <p>OAuth tokens are the motivating case (BUILD_PROMPT.md §7). Rather than relying on every future
 * developer remembering the rule, the type enforces it: {@link #toString()} and the Jackson
 * representation both yield a mask, so a token cannot leak through a log statement, a stack trace,
 * a debugger-friendly {@code toString}, or an accidentally-exposed DTO field.
 *
 * <p>Reading the real value requires calling {@link #reveal()}, which is deliberately conspicuous —
 * it should be easy to grep for and hard to do by accident.
 */
public final class SecretValue {

    private static final String MASK = "***REDACTED***";

    private final String value;

    private SecretValue(String value) {
        this.value = value;
    }

    public static SecretValue of(String value) {
        return new SecretValue(Objects.requireNonNull(value, "value"));
    }

    /** The actual secret. Call sites should be few and obvious. */
    public String reveal() {
        return value;
    }

    /** What Jackson writes, so a secret cannot escape through any API response. */
    @JsonValue
    public String masked() {
        return MASK;
    }

    @Override
    public String toString() {
        return MASK;
    }

    /**
     * Constant-time comparison: secrets are sometimes compared, and the timing of a naive {@code
     * equals} leaks how much of a prefix matched.
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SecretValue other)) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                other.value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
