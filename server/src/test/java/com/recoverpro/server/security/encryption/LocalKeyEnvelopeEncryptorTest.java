package com.recoverpro.server.security.encryption;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SYSTEM 07 TASK 7.2: a rotated key must not make previously-encrypted rows unreadable, and new
 * rows must be written under the current key. Also covers the two backward-compatibility paths:
 * a pre-versioning ciphertext (no kv&lt;N&gt; segment) reads as key version 1, and asking for a
 * key version that isn't loaded fails the same "wrong key" way corrupted ciphertext already did,
 * rather than crashing the row.
 */
class LocalKeyEnvelopeEncryptorTest {

    private static SecretKey randomKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return LocalKeyEnvelopeEncryptor.toSecretKey(raw);
    }

    @Test
    void rotatingKeys_oldCiphertextStillReadable_newCiphertextUsesNewKey() {
        SecretKey v1 = randomKey();
        SecretKey v2 = randomKey();

        LocalKeyEnvelopeEncryptor beforeRotation = new LocalKeyEnvelopeEncryptor(Map.of(1, v1), 1);
        String ciphertextV1 = beforeRotation.encrypt("borrower-phone-9999999999");
        assertThat(ciphertextV1).startsWith("enc:v1:kv1:");

        // Rotate: v2 becomes current, v1 stays loaded so old rows keep decrypting.
        LocalKeyEnvelopeEncryptor afterRotation = new LocalKeyEnvelopeEncryptor(Map.of(1, v1, 2, v2), 2);

        assertThat(afterRotation.decrypt(ciphertextV1)).isEqualTo("borrower-phone-9999999999");

        String ciphertextV2 = afterRotation.encrypt("borrower-phone-8888888888");
        assertThat(ciphertextV2).startsWith("enc:v1:kv2:");
        assertThat(afterRotation.decrypt(ciphertextV2)).isEqualTo("borrower-phone-8888888888");

        // The re-encryption job's fast-path signal: v1 ciphertext is stale, v2 is not.
        assertThat(afterRotation.needsRewrite(ciphertextV1)).isTrue();
        assertThat(afterRotation.needsRewrite(ciphertextV2)).isFalse();
    }

    @Test
    void legacyCiphertextWithNoVersionMarker_readsAsKeyVersion1() {
        SecretKey v1 = randomKey();
        LocalKeyEnvelopeEncryptor encryptor = new LocalKeyEnvelopeEncryptor(Map.of(1, v1), 1);

        // Reproduces exactly what the pre-TASK-7.2 encryptor used to write: "enc:v1:<base64>",
        // no "kvN:" segment -- there's no old build left to generate this with, so it's
        // hand-assembled the same way the legacy encrypt() did (raw AES-GCM, IV prefix, no
        // version marker).
        String legacyCiphertext = "enc:v1:" + rawAesGcmBase64(v1, "legacy-borrower-name");

        assertThat(encryptor.decrypt(legacyCiphertext)).isEqualTo("legacy-borrower-name");
    }

    @Test
    void retiredKeyVersionRemovedFromConfig_decryptFailsClosedNotWithAnException() {
        SecretKey v1 = randomKey();
        SecretKey v2 = randomKey();
        LocalKeyEnvelopeEncryptor beforeRetirement = new LocalKeyEnvelopeEncryptor(Map.of(1, v1, 2, v2), 2);
        String ciphertextV1 = beforeRetirement.encrypt("will-be-orphaned");
        // encrypt() always uses currentVersion (2) -- force a genuine v1 ciphertext to retire against.
        LocalKeyEnvelopeEncryptor v1Writer = new LocalKeyEnvelopeEncryptor(Map.of(1, v1), 1);
        String v1Ciphertext = v1Writer.encrypt("will-be-orphaned");

        // v1 retired from config (operator error: retired before the re-encryption job finished).
        LocalKeyEnvelopeEncryptor afterRetirement = new LocalKeyEnvelopeEncryptor(Map.of(2, v2), 2);

        assertThat(afterRetirement.decrypt(v1Ciphertext))
                .isEqualTo("[decryption failed]");
        // Confirms this doesn't also throw for the still-current version, i.e. the row-level
        // failure is scoped to the specific stale row, not the whole encryptor.
        String ciphertextV2 = afterRetirement.encrypt("still-fine");
        assertThat(afterRetirement.decrypt(ciphertextV2)).isEqualTo("still-fine");
    }

    @Test
    void currentVersionMustHaveAMatchingKey() {
        SecretKey v1 = randomKey();
        assertThatThrownBy(() -> new LocalKeyEnvelopeEncryptor(Map.of(1, v1), 2))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("current-key-version=2");
    }

    /** Hand-rolled AES/GCM to produce exactly the pre-versioning wire format for the backward-
     *  compatibility test above, independent of LocalKeyEnvelopeEncryptor's own (now-versioned)
     *  encrypt() method. */
    private static String rawAesGcmBase64(SecretKey key, String plaintext) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv);
            buf.put(ct);
            return java.util.Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
