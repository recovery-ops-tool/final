package com.recoverpro.server.scheduler;

import com.recoverpro.server.entity.FeatureFlag;
import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.entity.OrgSubscription;
import com.recoverpro.server.repository.FeatureFlagRepository;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.OpsAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM 18 TASK 18.2.c: exercises {@link OrganizationPurgeJob#purgeOne} directly (not the
 * {@code @Scheduled} entry point, which just loops candidates -- see this class's own javadoc for
 * why "purge" tombstones the row instead of deleting it).
 */
@ExtendWith(MockitoExtension.class)
class OrganizationPurgeJobTest {

    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrgSubscriptionRepository orgSubscriptionRepository;
    @Mock private FeatureFlagRepository featureFlagRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private OpsAlertService opsAlertService;

    private OrganizationPurgeJob job;

    @BeforeEach
    void setUp() {
        job = new OrganizationPurgeJob(organizationRepository, orgSubscriptionRepository,
                featureFlagRepository, userRepository, auditService, opsAlertService);
    }

    @Test
    void purgeOne_noRemainingUsers_tombstonesAndDeletesAdminRows() {
        UUID orgId = UUID.randomUUID();
        Organization org = Organization.builder().id(orgId).name("Old Name").code("OLDCODE")
                .contactEmail("contact@old.test").contactPhone("+911234567890")
                .lookupHashPepper("pepper").build();
        OrgSubscription sub = OrgSubscription.builder().id(UUID.randomUUID()).orgId(orgId).build();
        FeatureFlag flag = FeatureFlag.builder().id(UUID.randomUUID()).organizationId(orgId).build();

        when(userRepository.countByOrganizationId(orgId)).thenReturn(0L);
        when(orgSubscriptionRepository.findByOrgId(orgId)).thenReturn(java.util.Optional.of(sub));
        when(featureFlagRepository.findByOrganizationId(orgId)).thenReturn(List.of(flag));
        when(organizationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean result = job.purgeOne(org);

        assertThat(result).isTrue();
        assertThat(org.getName()).contains(orgId.toString());
        assertThat(org.getCode()).contains(orgId.toString());
        assertThat(org.getContactEmail()).isNull();
        assertThat(org.getContactPhone()).isNull();
        assertThat(org.getLookupHashPepper()).isNull();
        assertThat(org.getPurgedAt()).isNotNull();
        verify(orgSubscriptionRepository).delete(sub);
        verify(featureFlagRepository).deleteAll(List.of(flag));
        verify(organizationRepository, never()).delete(any());
        verify(organizationRepository, never()).deleteById(any());
        verify(auditService).record(argThat(evt ->
                evt.getAction() == com.recoverpro.server.enums.AuditAction.ORG_PURGED));
    }

    @Test
    void purgeOne_stillHasUsers_skipsWithoutMutating() {
        UUID orgId = UUID.randomUUID();
        Organization org = Organization.builder().id(orgId).name("Still Active").code("ACTIVE").build();
        when(userRepository.countByOrganizationId(orgId)).thenReturn(2L);

        boolean result = job.purgeOne(org);

        assertThat(result).isFalse();
        assertThat(org.getPurgedAt()).isNull();
        assertThat(org.getName()).isEqualTo("Still Active");
        verify(organizationRepository, never()).save(any());
        verify(orgSubscriptionRepository, never()).delete(any());
    }
}
