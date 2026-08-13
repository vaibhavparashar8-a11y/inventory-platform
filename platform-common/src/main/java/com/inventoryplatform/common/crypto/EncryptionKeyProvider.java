package com.inventoryplatform.common.crypto;

import javax.crypto.SecretKey;

/**
 * Supplies the keys used to encrypt credentials at rest.
 *
 * <p>Keys live outside the database (BUILD_PROMPT.md §7) — a backup of the data folder must not be
 * sufficient to read someone's marketplace tokens.
 *
 * <p>Lookup is by {@link KeyId} rather than "the current key" alone, because rotation has to be
 * possible without a schema change: rows encrypted under an old key stay readable while new writes
 * use the current one.
 */
public interface EncryptionKeyProvider {

    /** The key new ciphertext is written with. */
    KeyId currentKeyId();

    /**
     * @throws IllegalStateException if the key is unknown — better a loud failure than silently
     *     returning unreadable data
     */
    SecretKey keyFor(KeyId keyId);

    /**
     * Identifies which key encrypted a given value. Stored alongside the ciphertext.
     *
     * @param value short opaque identifier, e.g. {@code k1}
     */
    record KeyId(String value) {

        public KeyId {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Key id must not be blank");
            }
            if (value.contains(".")) {
                // The ciphertext envelope is dot-delimited; a dot here would make it ambiguous.
                throw new IllegalArgumentException("Key id must not contain '.'");
            }
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
