package com.recoverpro.server.security.encryption;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
public class EncryptionContext {

    private static volatile EnvelopeEncryptor INSTANCE;

    @Value("${app.encryption.enabled:true}")
    private boolean enabled;

    @Value("${app.encryption.provider:local}")
    private String provider;

    @Value("${app.encryption.key-base64:}")
    private String keyBase64;

    // SYSTEM 07 TASK 7.2: which version app.encryption.key-base64 IS right now. Bump this
    // (and move the old value into previous-keys-base64 below) as the whole rotation procedure --
    // see docs/RUNBOOK-KEY-ROTATION.md.
    @Value("${app.encryption.current-key-version:1}")
    private int currentKeyVersion;

    // Retired keys still needed to decrypt rows the background re-encryption job (PiiKeyRotationJob)
    // hasn't reached yet. Format: "v:base64key,v:base64key,..." -- e.g. "1:AbC123==". Never
    // include current-key-version's own number here; that's what app.encryption.key-base64 is for.
    @Value("${app.encryption.previous-keys-base64:}")
    private String previousKeysBase64;

    @Value("${app.encryption.kms.region:}")
    private String kmsRegion;

    @Value("${app.encryption.kms.key-id:}")
    private String kmsKeyId;

    @Bean
    public EnvelopeEncryptor envelopeEncryptor() {
        if (!enabled) {
            log.warn("PII encryption DISABLED (app.encryption.enabled=false). Set true in production.");
            return new DisabledEnvelopeEncryptor();
        }

        String p = provider == null ? "local" : provider.trim().toLowerCase();

        if ("kms".equals(p)) {
            if (kmsKeyId == null || kmsKeyId.isBlank()) {
                throw new EncryptionException(
                        "app.encryption.provider=kms but app.encryption.kms.key-id is empty");
            }
            log.info("PII encryption ENABLED via AWS KMS.");
            return new KmsEnvelopeEncryptor(kmsRegion, kmsKeyId);
        }

        if (!"local".equals(p)) {
            throw new EncryptionException(
                    "Unknown app.encryption.provider='" + provider + "' (use 'local' or 'kms')");
        }

        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new EncryptionException(
                    "app.encryption.enabled=true (provider=local) but app.encryption.key-base64 is empty. "
                            + "Refusing to start: an auto-generated ephemeral key would make encrypted data "
                            + "unreadable after every restart.");
        }

        Map<Integer, SecretKey> keysByVersion = new HashMap<>();
        keysByVersion.put(currentKeyVersion, LocalKeyEnvelopeEncryptor.toSecretKey(decodeKey(
                keyBase64, "app.encryption.key-base64")));

        for (String entry : splitPreviousKeys()) {
            int colon = entry.indexOf(':');
            if (colon < 0) {
                throw new EncryptionException(
                        "app.encryption.previous-keys-base64 entry '" + entry
                                + "' is not in 'version:base64key' form");
            }
            int version;
            try {
                version = Integer.parseInt(entry.substring(0, colon).trim());
            } catch (NumberFormatException e) {
                throw new EncryptionException(
                        "app.encryption.previous-keys-base64 entry '" + entry + "' has a non-numeric version");
            }
            if (version == currentKeyVersion) {
                throw new EncryptionException(
                        "app.encryption.previous-keys-base64 includes version " + version
                                + ", which is also app.encryption.current-key-version -- a key version must "
                                + "appear in exactly one of the two places, not both");
            }
            if (keysByVersion.containsKey(version)) {
                throw new EncryptionException(
                        "app.encryption.previous-keys-base64 lists key version " + version + " more than once");
            }
            keysByVersion.put(version, LocalKeyEnvelopeEncryptor.toSecretKey(decodeKey(
                    entry.substring(colon + 1).trim(), "app.encryption.previous-keys-base64[" + version + "]")));
        }

        log.info("PII encryption ENABLED with AES-256-GCM. currentKeyVersion={}, totalKeyVersionsLoaded={}",
                currentKeyVersion, keysByVersion.size());
        return new LocalKeyEnvelopeEncryptor(keysByVersion, currentKeyVersion);
    }

    private String[] splitPreviousKeys() {
        if (previousKeysBase64 == null || previousKeysBase64.isBlank()) return new String[0];
        return previousKeysBase64.split(",");
    }

    private static byte[] decodeKey(String base64, String propertyDescription) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new EncryptionException(propertyDescription + " is not valid base64", e);
        }
    }

    public static EnvelopeEncryptor encryptor() {
        EnvelopeEncryptor i = INSTANCE;
        if (i == null) {
            throw new EncryptionException(
                    "EncryptionContext not initialized — accessed before Spring context is ready.");
        }
        return i;
    }

    @Component
    static class StaticPublisher {
        StaticPublisher(EnvelopeEncryptor encryptor) {
            INSTANCE = encryptor;
        }
    }
}
