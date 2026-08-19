package com.recoverpro.server.security.encryption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * SYSTEM 07 TASK 7.2: supports multiple key versions so an encryption key can be rotated without
 * making previously-encrypted rows unreadable. New writes always use {@code currentVersion}'s
 * key; {@link #decrypt} resolves whichever key version a given ciphertext was actually written
 * under. A ciphertext written before this versioning existed (plain {@code enc:v1:<payload>},
 * no {@code kv<N>} segment) is treated as key version 1 -- the one format that predates this
 * change can only ever mean "whatever the key was before rotation was even a concept," which
 * is version 1 by definition.
 */
public class LocalKeyEnvelopeEncryptor implements EnvelopeEncryptor {

    private static final Logger log = LoggerFactory.getLogger(LocalKeyEnvelopeEncryptor.class);

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN  = 12;
    private static final int TAG_BITS = 128;
    private static final String LEGACY_VERSION_MARKER = "kv";

    /**
     * Returned in place of a value whose ciphertext fails to authenticate (wrong key or
     * tampering) so one corrupted PII field can't take down an entire listing query — the
     * alternative is letting Hibernate's row hydration throw and turn a single bad row into a
     * 500 for every other, perfectly readable row in the same page.
     */
    static final String DECRYPTION_FAILED_PLACEHOLDER = "[decryption failed]";

    private final Map<Integer, SecretKey> keysByVersion;
    private final int currentVersion;
    private final SecureRandom random = new SecureRandom();

    /** Single-key constructor -- version 1, current. Covers the common case (no rotation ever
     *  performed yet) without callers needing to build a Map for it. */
    public LocalKeyEnvelopeEncryptor(byte[] keyBytes) {
        this(Map.of(1, toSecretKey(keyBytes)), 1);
    }

    public LocalKeyEnvelopeEncryptor(Map<Integer, SecretKey> keysByVersion, int currentVersion) {
        if (keysByVersion == null || keysByVersion.isEmpty()) {
            throw new EncryptionException("At least one encryption key version is required");
        }
        if (!keysByVersion.containsKey(currentVersion)) {
            throw new EncryptionException(
                    "app.encryption.current-key-version=" + currentVersion
                            + " has no matching key configured");
        }
        this.keysByVersion = new TreeMap<>(keysByVersion);
        this.currentVersion = currentVersion;
    }

    static SecretKey toSecretKey(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new EncryptionException(
                    "AES-256 key must be 32 bytes; got "
                            + (keyBytes == null ? "null" : keyBytes.length + " bytes"));
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, keysByVersion.get(currentVersion), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv);
            buf.put(ct);
            return CIPHERTEXT_PREFIX + LEGACY_VERSION_MARKER + currentVersion + ":"
                    + Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new EncryptionException("Encryption failed", e);
        }
    }

    @Override
    public String decrypt(String storedValue) {
        if (storedValue == null) return null;
        if (!storedValue.startsWith(CIPHERTEXT_PREFIX)) return storedValue;

        String rest = storedValue.substring(CIPHERTEXT_PREFIX.length());
        int version;
        String payload;
        if (rest.startsWith(LEGACY_VERSION_MARKER)) {
            int colon = rest.indexOf(':');
            if (colon < 0) {
                log.error("PII decryption failed (malformed versioned ciphertext); returning redacted placeholder");
                return DECRYPTION_FAILED_PLACEHOLDER;
            }
            try {
                version = Integer.parseInt(rest.substring(LEGACY_VERSION_MARKER.length(), colon));
            } catch (NumberFormatException e) {
                log.error("PII decryption failed (unparseable key version); returning redacted placeholder");
                return DECRYPTION_FAILED_PLACEHOLDER;
            }
            payload = rest.substring(colon + 1);
        } else {
            // No kv<N> segment at all -- ciphertext predates key versioning, implicitly v1.
            version = 1;
            payload = rest;
        }

        SecretKey key = keysByVersion.get(version);
        if (key == null) {
            log.error("PII decryption failed (key version {} not configured -- retired without a "
                    + "completed re-encryption pass?); returning redacted placeholder", version);
            return DECRYPTION_FAILED_PLACEHOLDER;
        }

        try {
            byte[] all = Base64.getDecoder().decode(payload);
            if (all.length <= IV_LEN) throw new EncryptionException("Ciphertext payload too short");
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Never log storedValue itself -- it's either ciphertext (useless without the key)
            // or, if something upstream already mangled it, potentially raw PII.
            log.error("PII decryption failed (tampering or wrong key?); returning redacted placeholder", e);
            return DECRYPTION_FAILED_PLACEHOLDER;
        }
    }

    @Override
    public boolean needsRewrite(String storedValue) {
        if (storedValue == null || !storedValue.startsWith(CIPHERTEXT_PREFIX)) return false;
        String rest = storedValue.substring(CIPHERTEXT_PREFIX.length());
        if (!rest.startsWith(LEGACY_VERSION_MARKER)) {
            // Legacy pre-versioning format -- worth normalizing even if currentVersion == 1,
            // since it's implicitly correct today but silently unreadable the moment version 1
            // is ever retired.
            return true;
        }
        int colon = rest.indexOf(':');
        if (colon < 0) return false; // malformed; decrypt() will report it, not this job's problem
        try {
            return Integer.parseInt(rest.substring(LEGACY_VERSION_MARKER.length(), colon)) != currentVersion;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public int currentVersion() {
        return currentVersion;
    }
}
