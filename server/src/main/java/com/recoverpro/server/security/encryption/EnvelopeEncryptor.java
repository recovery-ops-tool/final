package com.recoverpro.server.security.encryption;

public interface EnvelopeEncryptor {

    String CIPHERTEXT_PREFIX = "enc:v1:";

    String encrypt(String plaintext);

    String decrypt(String storedValue);

    /**
     * SYSTEM 07 TASK 7.2: true if re-encrypting {@code storedValue} (decrypt then encrypt again)
     * would produce a different stored representation than what's there now -- i.e. it was
     * written under a key version older than the current one. Used by the background
     * re-encryption job to find rows that still need migrating after a key rotation, without the
     * job needing to know anything about a specific provider's ciphertext format.
     * <p>
     * Local-key rotation is the only case where this can ever be true: KMS embeds the key
     * identity in the ciphertext blob itself and resolves it automatically on decrypt regardless
     * of which key ARN is configured for new encrypts, so a KMS-backed value never "needs"
     * rewriting for correctness (see {@link KmsEnvelopeEncryptor}). Default false covers both
     * {@link KmsEnvelopeEncryptor} and {@link DisabledEnvelopeEncryptor}.
     */
    default boolean needsRewrite(String storedValue) {
        return false;
    }
}
