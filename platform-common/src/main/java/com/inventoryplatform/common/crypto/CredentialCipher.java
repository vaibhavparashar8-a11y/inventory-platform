package com.inventoryplatform.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts credentials for storage, using AES-GCM.
 *
 * <p>GCM rather than CBC because it authenticates as well as encrypts: a ciphertext altered in the
 * database fails to decrypt rather than yielding plausible garbage. On a customer's PC, where the
 * database file is readable by anything running as that user, tamper-evidence is worth as much as
 * secrecy.
 *
 * <p>Envelope format, deliberately self-describing so a stored value can always be interpreted:
 *
 * <pre>v1.&lt;keyId&gt;.&lt;base64url iv&gt;.&lt;base64url ciphertext+tag&gt;</pre>
 *
 * <p>The version prefix and key id are what make rotation and algorithm change possible later
 * without a migration: old values say how to read themselves.
 */
public final class CredentialCipher {

    private static final String VERSION = "v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final EncryptionKeyProvider keyProvider;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(EncryptionKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    /** Encrypts under the current key. A fresh IV is generated per call — never reuse one in GCM. */
    public String encrypt(SecretValue plaintext) {
        EncryptionKeyProvider.KeyId keyId = keyProvider.currentKeyId();

        byte[] iv = new byte[IV_LENGTH_BYTES];
        random.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    keyProvider.keyFor(keyId),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.reveal().getBytes(StandardCharsets.UTF_8));

            return String.join(
                    ".", VERSION, keyId.value(), encode(iv), encode(ciphertext));
        } catch (GeneralSecurityException e) {
            // Deliberately no plaintext, no key material, and no cause detail in the message.
            throw new IllegalStateException("Credential could not be encrypted", e);
        }
    }

    /**
     * @throws IllegalStateException if the envelope is malformed, the key is unknown, or the
     *     authentication tag does not verify — all indistinguishable to a caller on purpose
     */
    public SecretValue decrypt(String envelope) {
        String[] parts = envelope.split("\\.", 4);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw new IllegalStateException("Stored credential is not in a recognised format");
        }

        EncryptionKeyProvider.KeyId keyId = new EncryptionKeyProvider.KeyId(parts[1]);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    keyProvider.keyFor(keyId),
                    new GCMParameterSpec(TAG_LENGTH_BITS, decode(parts[2])));

            byte[] plaintext = cipher.doFinal(decode(parts[3]));
            return SecretValue.of(new String(plaintext, StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Stored credential could not be decrypted", e);
        }
    }

    /** Which key a stored value was written under — needed to plan a rotation. */
    public EncryptionKeyProvider.KeyId keyIdOf(String envelope) {
        String[] parts = envelope.split("\\.", 4);
        if (parts.length != 4) {
            throw new IllegalStateException("Stored credential is not in a recognised format");
        }
        return new EncryptionKeyProvider.KeyId(parts[1]);
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
