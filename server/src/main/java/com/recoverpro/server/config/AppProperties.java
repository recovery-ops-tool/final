package com.recoverpro.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String baseUrl = "http://localhost:5173";
    private Jwt jwt = new Jwt();
    private Security security = new Security();
    private Billing billing = new Billing();
    private Audit audit = new Audit();

    @Data
    public static class Billing {
        /** Days a subscription may stay PAST_DUE, with access retained, before dunning
         *  auto-cancels it (Billing Ledger design doc §12/§13). Placeholder default pending
         *  business confirmation, same as every other dunning default in that design doc. */
        private int dunningGracePeriodDays = 7;
    }

    @Data
    public static class Jwt {
        private String secret;
        private long accessTokenExpiryMs = 900_000L;
        private long refreshTokenExpiryMs = 604_800_000L;
    }

    @Data
    public static class Audit {
        /** SYSTEM 10 TASK 10.3: signed off 2026-08-19 (docs/AUDIT-RETENTION.md) -- security and
         *  financial events retained 7 years. Partition-drop is table-wide, not per-category (see
         *  that doc for why), so this is unified_audit_events' single horizon even though a
         *  minority of its rows (FILE_PROCESSING_*, REPORT_GENERATED) are more operational. */
        private int unifiedAuditEventsRetentionYears = 7;

        /** Signed off alongside the above -- user_action_audit_logs is a purely operational,
         *  free-text "who clicked what" trail with no billing/RBAC content. */
        private int userActionAuditLogsRetentionMonths = 24;

        /** Dry-run by default -- a retention job that drops the wrong partition is unrecoverable
         *  (DROP TABLE, not DELETE; nothing to roll back). Must be explicitly enabled per
         *  environment once the dry-run log output has been reviewed. */
        private boolean retentionPurgeEnabled = false;
    }

    @Data
    public static class Security {
        private int maxLoginAttempts = 5;
        private int loginWindowMinutes = 15;
        private int lockoutDurationMinutes = 30;
        private int otpExpiryMinutes = 10;
        private int welcomeOtpExpiryMinutes = 1440;
        private int bcryptStrength = 12;
        private int forgotPasswordMaxAttempts = 3;
        private int forgotPasswordWindowMinutes = 30;
        private int otpMaxAttempts = 5;
        private int otpWindowMinutes = 10;
        private int contactFormMaxAttempts = 5;
        private int contactFormWindowMinutes = 60;
        // SYSTEM 07 TASK 7.3: file upload and report generation both trigger real background
        // work (parsing/PII-encrypting a whole file; a DB aggregation + export job) -- an
        // authenticated-but-compromised or scripted-misuse account could otherwise queue an
        // unbounded number of these back-to-back.
        private int fileUploadMaxAttempts = 10;
        private int fileUploadWindowMinutes = 5;
        private int reportGenerateMaxAttempts = 10;
        private int reportGenerateWindowMinutes = 5;
        // TASK 35.4.b: "exports move bulk PII out of the system -- treat them accordingly."
        // Report generation was already rate-limited (above); the actual file-download step
        // (ExportController) was not.
        private int reportDownloadMaxAttempts = 30;
        private int reportDownloadWindowMinutes = 5;
    }
}
