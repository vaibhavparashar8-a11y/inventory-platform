package com.inventoryplatform.common.idempotency;

import java.util.Objects;

/**
 * A caller-generated key identifying one logical state-changing operation.
 *
 * <p>Required on every state-changing call to {@code stock-service} (BUILD_PROMPT.md §3). Retries
 * are certain — a timeout, a dropped connection, an impatient double-click — and a double decrement
 * is a wrong stock count the customer finds weeks later.
 *
 * @param value the key, as supplied in the {@code Idempotency-Key} header
 */
public record IdempotencyKey(String value) {

    /** The IETF-draft header name, used verbatim so the semantics are unsurprising. */
    public static final String HEADER = "Idempotency-Key";

    private static final int MAX_LENGTH = 128;

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Idempotency key must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency key must be at most " + MAX_LENGTH + " characters");
        }
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
