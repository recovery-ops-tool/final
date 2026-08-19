package com.recoverpro.server.scheduler;

import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.repository.FeatureFlagRepository;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.OpsAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * SYSTEM 18 TASK 18.2.c: the other half of {@code PlatformOrganizationController#delete} --
 * that endpoint only starts the retention window (soft delete), this job finishes it once the
 * window has elapsed.
 *
 * <p>"Purge" here means tombstone, not row-delete: name/code/contact fields are scrubbed
 * (irreversibly -- the original values are gone) and the org's own {@code OrgSubscription} and
 * org-scoped {@code FeatureFlag} rows are deleted, but the {@code Organization} row itself
 * survives with {@code purgedAt} set. That is a deliberate structural necessity, not a
 * half-measure: {@code unified_audit_events.organization_id} is a hard FK to this table, and
 * {@code trg_unified_audit_events_immutable} (V085, reusing V006's audit-immutability function)
 * blocks UPDATE as well as DELETE on every audit row -- including the implicit UPDATE Postgres
 * would issue for an {@code ON DELETE SET NULL} action, so even that softer FK action is blocked.
 * An org that ever generated a single audit event (which is every org, starting with its own
 * creation) cannot have its row physically deleted without either weakening the audit trail's
 * immutability guarantee or breaking referential integrity -- neither is acceptable, so this
 * mirrors the position TASK 18.4.c takes for user erasure: keep the row, scrub what it exposes,
 * tombstone the rest.
 *
 * <p>Deliberately does NOT cascade into business data (allocations, borrowers, ptp records, ...)
 * -- {@code PlatformOrganizationController#delete} already refuses to soft-delete an org that
 * still has users, so the common case reaching this job is an empty shell with nothing else to
 * purge. If a soft-deleted org unexpectedly still has users when this runs, it is skipped and
 * flagged rather than purged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrganizationPurgeJob {

    private final OrganizationRepository organizationRepository;
    private final OrgSubscriptionRepository orgSubscriptionRepository;
    private final FeatureFlagRepository featureFlagRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final OpsAlertService opsAlertService;

    @Value("${app.org-retention.deletion-window-days:30}")
    private int deletionRetentionDays;

    /** Runs at 02:30 daily, off-peak and after PiiKeyRotationJob's 01:30 slot. */
    @Scheduled(cron = "0 30 2 * * *")
    @SchedulerLock(name = "organization_purge", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void purgeExpiredDeletions() {
        Instant cutoff = Instant.now().minus(deletionRetentionDays, ChronoUnit.DAYS);
        List<Organization> candidates = organizationRepository.findAll().stream()
                .filter(o -> o.getDeletedAt() != null && o.getDeletedAt().isBefore(cutoff))
                .toList();

        int purged = 0;
        int skipped = 0;
        for (Organization org : candidates) {
            if (purgeOne(org)) {
                purged++;
            } else {
                skipped++;
            }
        }

        if (purged > 0) {
            log.info("OrganizationPurgeJob: purged {} organization(s) past the {}-day retention window",
                    purged, deletionRetentionDays);
        }
        if (skipped > 0) {
            opsAlertService.alertJobFailure("OrganizationPurgeJob.purgeExpiredDeletions",
                    skipped + " organization(s) past retention could not be purged -- still have "
                            + "users attached despite being soft-deleted; investigate manually", null);
        }
    }

    @Transactional
    boolean purgeOne(Organization org) {
        long remainingUsers = userRepository.countByOrganizationId(org.getId());
        if (remainingUsers > 0) {
            log.warn("OrganizationPurgeJob: org {} has {} user(s) despite being soft-deleted -- skipping",
                    org.getId(), remainingUsers);
            return false;
        }

        String originalName = org.getName();
        String originalCode = org.getCode();
        String tombstone = "[deleted-" + org.getId() + "]";

        orgSubscriptionRepository.findByOrgId(org.getId()).ifPresent(orgSubscriptionRepository::delete);
        featureFlagRepository.deleteAll(featureFlagRepository.findByOrganizationId(org.getId()));

        org.setName(tombstone);
        org.setCode("DELETED-" + org.getId());
        org.setContactEmail(null);
        org.setContactPhone(null);
        org.setLookupHashPepper(null);
        org.setPurgedAt(Instant.now());
        organizationRepository.save(org);

        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.ORG_PURGED)
                .resourceType(AuditResourceType.ORGANIZATION)
                .resourceId(org.getId().toString())
                .organizationIdOverride(org.getId())
                .reason("Retention window (" + deletionRetentionDays + "d) elapsed")
                .beforeState(Map.of("name", originalName, "code", originalCode,
                        "deletedAt", String.valueOf(org.getDeletedAt())))
                .build());
        return true;
    }
}
