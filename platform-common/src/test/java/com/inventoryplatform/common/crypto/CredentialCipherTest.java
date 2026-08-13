package com.inventoryplatform.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

/**
 * BUILD_PROMPT.md §9 requires proving that credentials never serialise in the clear, and §7 that
 * tokens never appear in logs, traces or any API response.
 */
class CredentialCipherTest {

    private static final String TOKEN = "fk-oauth-access-token-value-12345";

    @TempDir Path tempDir;

    private CredentialCipher cipher;

    @BeforeEach
    void setUp() {
        cipher = new CredentialCipher(new FileEncryptionKeyProvider(tempDir.resolve("keys.properties")));
    }

    @Test
    void roundTripsAValue() {
        String envelope = cipher.encrypt(SecretValue.of(TOKEN));

        assertThat(cipher.decrypt(envelope).reveal()).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("the plaintext never appears in the stored envelope")
    void envelopeDoesNotLeakPlaintext() {
        String envelope = cipher.encrypt(SecretValue.of(TOKEN));

        assertThat(envelope).doesNotContain(TOKEN);
        assertThat(envelope).startsWith("v1.k1.");
    }

    @Test
    @DisplayName("encrypting twice gives different ciphertext — the IV is never reused")
    void ivIsFreshPerEncryption() {
        String first = cipher.encrypt(SecretValue.of(TOKEN));
        String second = cipher.encrypt(SecretValue.of(TOKEN));

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first).reveal()).isEqualTo(cipher.decrypt(second).reveal());
    }

    @Test
    @DisplayName("a tampered ciphertext fails to decrypt rather than yielding garbage")
    void tamperingIsDetected() {
        String envelope = cipher.encrypt(SecretValue.of(TOKEN));

        // Flip a character in the ciphertext segment.
        String[] parts = envelope.split("\\.");
        char[] ciphertext = parts[3].toCharArray();
        ciphertext[0] = ciphertext[0] == 'A' ? 'B' : 'A';
        String tampered = String.join(".", parts[0], parts[1], parts[2], new String(ciphertext));

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be decrypted");
    }

    @Test
    void malformedEnvelopeIsRejected() {
        assertThatThrownBy(() -> cipher.decrypt("not-an-envelope"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in a recognised format");
    }

    @Test
    @DisplayName("a value encrypted under an unavailable key fails loudly, not silently")
    void unknownKeyIsRejected() {
        String envelope = cipher.encrypt(SecretValue.of(TOKEN));
        String underOtherKey = envelope.replaceFirst("^v1\\.k1\\.", "v1.k99.");

        assertThatThrownBy(() -> cipher.decrypt(underOtherKey))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyIdIsRecoverableForRotationPlanning() {
        String envelope = cipher.encrypt(SecretValue.of(TOKEN));

        assertThat(cipher.keyIdOf(envelope)).isEqualTo(new EncryptionKeyProvider.KeyId("k1"));
    }

    @Test
    @DisplayName("keys persist across restarts, or every stored credential would be unreadable")
    void keysArePersisted() {
        String envelope = cipher.encrypt(SecretValue.of(TOKEN));

        CredentialCipher afterRestart =
                new CredentialCipher(new FileEncryptionKeyProvider(tempDir.resolve("keys.properties")));

        assertThat(afterRestart.decrypt(envelope).reveal()).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("a secret cannot escape through JSON, toString, or a log statement")
    void secretValueIsRedactedEverywhere() {
        record CredentialResponse(String firmId, SecretValue accessToken) {}

        SecretValue secret = SecretValue.of(TOKEN);
        String json =
                JsonMapper.builder().build().writeValueAsString(new CredentialResponse("f1", secret));

        assertThat(json).doesNotContain(TOKEN);
        assertThat(json).contains("***REDACTED***");
        assertThat(secret.toString()).doesNotContain(TOKEN);
        assertThat("token=" + secret).doesNotContain(TOKEN);
        assertThat(secret.reveal()).isEqualTo(TOKEN);
    }
}
