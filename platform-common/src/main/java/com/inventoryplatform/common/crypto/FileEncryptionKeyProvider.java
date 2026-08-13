package com.inventoryplatform.common.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps encryption keys in a file outside the database, generating one on first run.
 *
 * <p>Deliberately not in the database: the backup-and-restore feature (Phase 7) copies the data
 * folder, and a backup that also contains the key protects nothing. Keeping them apart means a
 * stolen backup is inert.
 *
 * <p>The file holds every key ever used, keyed by id, so rotation adds a key rather than replacing
 * one — values encrypted under an old key must stay readable.
 *
 * <p><strong>Known limitation:</strong> file permissions are restricted via POSIX where the
 * filesystem supports it. On Windows — the primary target — POSIX permissions do not apply and the
 * file inherits directory ACLs. Tightening Windows ACLs is tracked as a known issue rather than
 * silently skipped.
 */
public final class FileEncryptionKeyProvider implements EncryptionKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(FileEncryptionKeyProvider.class);

    private static final String ALGORITHM = "AES";
    private static final int KEY_SIZE_BITS = 256;
    private static final KeyId INITIAL_KEY_ID = new KeyId("k1");

    private final Map<KeyId, SecretKey> keys = new HashMap<>();
    private final KeyId currentKeyId;

    public FileEncryptionKeyProvider(Path keyFile) {
        if (Files.exists(keyFile)) {
            this.keys.putAll(load(keyFile));
            this.currentKeyId = newest(keys.keySet());
        } else {
            SecretKey generated = generateKey();
            this.keys.put(INITIAL_KEY_ID, generated);
            this.currentKeyId = INITIAL_KEY_ID;
            store(keyFile, keys);
            log.info("Generated a new credential encryption key at {}", keyFile);
        }
    }

    @Override
    public KeyId currentKeyId() {
        return currentKeyId;
    }

    @Override
    public SecretKey keyFor(KeyId keyId) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException(
                    "No encryption key '%s' is available. Stored credentials written under it cannot be "
                            .formatted(keyId.value())
                            + "read; restore the key file from backup.");
        }
        return key;
    }

    private static SecretKey generateKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance(ALGORITHM);
            generator.init(KEY_SIZE_BITS);
            return generator.generateKey();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("AES is required but unavailable", e);
        }
    }

    private static Map<KeyId, SecretKey> load(Path keyFile) {
        Map<KeyId, SecretKey> loaded = new HashMap<>();
        try {
            for (String line : Files.readAllLines(keyFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("=", 2);
                if (parts.length != 2) {
                    throw new IllegalStateException("Key file line is malformed");
                }
                byte[] material = Base64.getDecoder().decode(parts[1]);
                loaded.put(new KeyId(parts[0]), new SecretKeySpec(material, ALGORITHM));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Credential encryption key file could not be read", e);
        }

        if (loaded.isEmpty()) {
            throw new IllegalStateException("Credential encryption key file contains no keys");
        }
        return loaded;
    }

    private static void store(Path keyFile, Map<KeyId, SecretKey> keys) {
        StringBuilder content = new StringBuilder();
        content.append("# Credential encryption keys. Keep out of the database and out of backups.\n");
        content.append("# Losing this file makes stored marketplace tokens unreadable.\n");
        keys.forEach(
                (id, key) ->
                        content
                                .append(id.value())
                                .append('=')
                                .append(Base64.getEncoder().encodeToString(key.getEncoded()))
                                .append('\n'));

        try {
            Path parent = keyFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(keyFile, content.toString(), StandardCharsets.UTF_8);
            restrictPermissions(keyFile);
        } catch (IOException e) {
            throw new IllegalStateException("Credential encryption key file could not be written", e);
        }
    }

    private static void restrictPermissions(Path keyFile) {
        try {
            Files.setPosixFilePermissions(
                    keyFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: POSIX permissions do not apply. Tracked as a known issue.
            log.warn(
                    "Could not restrict permissions on the key file {}; it inherits directory ACLs",
                    keyFile);
        }
    }

    /** Keys are named k1, k2, …; the highest suffix is the newest. */
    private static KeyId newest(Set<KeyId> keyIds) {
        return keyIds.stream()
                .max(java.util.Comparator.comparingInt(FileEncryptionKeyProvider::suffixOf))
                .orElseThrow(() -> new IllegalStateException("No keys available"));
    }

    private static int suffixOf(KeyId keyId) {
        try {
            return Integer.parseInt(keyId.value().replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
