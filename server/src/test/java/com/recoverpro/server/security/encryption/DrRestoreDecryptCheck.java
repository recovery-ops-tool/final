package com.recoverpro.server.security.encryption;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;

/**
 * Manual DR-restore verification tool, NOT a JUnit test (no Surefire-matching name, never run by
 * `mvn test` or CI). Run by hand against a freshly restored database as part of the quarterly
 * restore-test procedure in docs/RUNBOOK-DR.md, to confirm PII encrypted under the deployment's
 * key is still decryptable post-restore. Never logs decrypted plaintext -- only pass/fail counts.
 *
 * Usage:
 *   java -cp <classpath> com.recoverpro.server.security.encryption.DrRestoreDecryptCheck \
 *        <jdbcUrl> <dbUser> <dbPassword> <pIIEncryptionKeyBase64>
 */
public final class DrRestoreDecryptCheck {

    private DrRestoreDecryptCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: DrRestoreDecryptCheck <jdbcUrl> <dbUser> <dbPassword> <keyBase64>");
            System.exit(2);
        }
        String jdbcUrl = args[0];
        String dbUser = args[1];
        String dbPassword = args[2];
        byte[] keyBytes = Base64.getDecoder().decode(args[3]);

        LocalKeyEnvelopeEncryptor encryptor = new LocalKeyEnvelopeEncryptor(keyBytes);

        int checked = 0;
        int decrypted = 0;
        int failed = 0;
        int plainOrNull = 0;

        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT first_name, last_name, mfa_secret FROM users")) {

            while (rs.next()) {
                for (int col = 1; col <= 3; col++) {
                    String stored = rs.getString(col);
                    checked++;
                    if (stored == null) {
                        plainOrNull++;
                        continue;
                    }
                    if (!stored.startsWith(EnvelopeEncryptor.CIPHERTEXT_PREFIX)) {
                        plainOrNull++;
                        continue;
                    }
                    String plain = encryptor.decrypt(stored);
                    if ("[decryption failed]".equals(plain)) {
                        failed++;
                    } else {
                        decrypted++;
                    }
                }
            }
        }

        System.out.println("DR restore decryption check:");
        System.out.println("  columns checked      = " + checked);
        System.out.println("  successfully decrypted = " + decrypted);
        System.out.println("  decryption FAILED     = " + failed);
        System.out.println("  null/unencrypted      = " + plainOrNull);

        if (failed > 0 || decrypted == 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }
}
