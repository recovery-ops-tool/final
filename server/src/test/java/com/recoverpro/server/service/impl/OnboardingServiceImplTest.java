package com.recoverpro.server.service.impl;

import com.recoverpro.server.dto.response.OnboardingChecklistResponse;
import com.recoverpro.server.entity.FileUpload;
import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.enums.FileUploadStatus;
import com.recoverpro.server.enums.UploadType;
import com.recoverpro.server.repository.AssignmentRepository;
import com.recoverpro.server.repository.ColumnSchemaRepository;
import com.recoverpro.server.repository.FileUploadRepository;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-PLAN 28.3: every checklist step is a live check against its own backing table (not a
 * maintained onboarding-progress row that could drift). Covers each step true/false independently
 * and the {@code allComplete}/time-to-first-import derived fields.
 */
@ExtendWith(MockitoExtension.class)
class OnboardingServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private ColumnSchemaRepository columnSchemaRepository;
    @Mock private FileUploadRepository fileUploadRepository;
    @Mock private AssignmentRepository assignmentRepository;
    @Mock private OrganizationRepository organizationRepository;

    private OnboardingServiceImpl service;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        service = new OnboardingServiceImpl(
                userRepository, columnSchemaRepository, fileUploadRepository, assignmentRepository, organizationRepository);
        orgId = UUID.randomUUID();
        lenient().when(userRepository.countByOrganizationId(orgId)).thenReturn(1L);
        lenient().when(columnSchemaRepository.existsByOrganizationId(orgId)).thenReturn(false);
        lenient().when(fileUploadRepository.findFirstCompletedByOrganizationIdAndUploadType(
                eq(orgId), eq(UploadType.ALLOCATION), any())).thenReturn(Optional.empty());
        lenient().when(assignmentRepository.existsByOrganizationIdAndIsDeletedFalse(orgId)).thenReturn(false);
    }

    @Test
    void freshOrg_nothingComplete() {
        OnboardingChecklistResponse result = service.getChecklist(orgId);

        assertThat(result.isTeamInvited()).isFalse();
        assertThat(result.isColumnSchemaConfigured()).isFalse();
        assertThat(result.isFirstFileImported()).isFalse();
        assertThat(result.isFirstAssignmentRun()).isFalse();
        assertThat(result.isAllComplete()).isFalse();
        assertThat(result.getTimeToFirstImportMinutes()).isNull();
    }

    /** The org's own initial admin (always created by PlatformOrganizationController.create())
     *  must not, by itself, make this step true. */
    @Test
    void teamInvited_onlyTheInitialAdminExists_isFalse() {
        when(userRepository.countByOrganizationId(orgId)).thenReturn(1L);

        assertThat(service.getChecklist(orgId).isTeamInvited()).isFalse();
    }

    @Test
    void teamInvited_asecondUserExists_isTrue() {
        when(userRepository.countByOrganizationId(orgId)).thenReturn(2L);

        assertThat(service.getChecklist(orgId).isTeamInvited()).isTrue();
    }

    @Test
    void columnSchemaConfigured_reflectsRepository() {
        when(columnSchemaRepository.existsByOrganizationId(orgId)).thenReturn(true);

        assertThat(service.getChecklist(orgId).isColumnSchemaConfigured()).isTrue();
    }

    @Test
    void firstAssignmentRun_reflectsRepository() {
        when(assignmentRepository.existsByOrganizationIdAndIsDeletedFalse(orgId)).thenReturn(true);

        assertThat(service.getChecklist(orgId).isFirstAssignmentRun()).isTrue();
    }

    @Test
    void firstFileImported_computesTimeToFirstImportFromOrgCreation() {
        Instant orgCreated = Instant.now().minus(3, ChronoUnit.DAYS);
        Instant firstUploadAt = orgCreated.plus(90, ChronoUnit.MINUTES);

        when(fileUploadRepository.findFirstCompletedByOrganizationIdAndUploadType(
                eq(orgId), eq(UploadType.ALLOCATION),
                eq(List.of(FileUploadStatus.COMPLETED, FileUploadStatus.PARTIALLY_COMPLETED))))
                .thenReturn(Optional.of(FileUpload.builder().createdAt(firstUploadAt).build()));
        when(organizationRepository.findById(orgId))
                .thenReturn(Optional.of(Organization.builder().id(orgId).createdAt(orgCreated).build()));

        OnboardingChecklistResponse result = service.getChecklist(orgId);

        assertThat(result.isFirstFileImported()).isTrue();
        assertThat(result.getTimeToFirstImportMinutes()).isEqualTo(90L);
    }

    @Test
    void allSteps_true_allCompleteIsTrue() {
        when(userRepository.countByOrganizationId(orgId)).thenReturn(3L);
        when(columnSchemaRepository.existsByOrganizationId(orgId)).thenReturn(true);
        when(assignmentRepository.existsByOrganizationIdAndIsDeletedFalse(orgId)).thenReturn(true);
        when(fileUploadRepository.findFirstCompletedByOrganizationIdAndUploadType(
                eq(orgId), eq(UploadType.ALLOCATION), any()))
                .thenReturn(Optional.of(FileUpload.builder().createdAt(Instant.now()).build()));
        when(organizationRepository.findById(orgId))
                .thenReturn(Optional.of(Organization.builder().id(orgId).createdAt(Instant.now()).build()));

        assertThat(service.getChecklist(orgId).isAllComplete()).isTrue();
    }
}
