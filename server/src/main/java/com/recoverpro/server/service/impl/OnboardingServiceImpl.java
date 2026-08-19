package com.recoverpro.server.service.impl;

import com.recoverpro.server.dto.response.OnboardingChecklistResponse;
import com.recoverpro.server.entity.FileUpload;
import com.recoverpro.server.enums.FileUploadStatus;
import com.recoverpro.server.enums.UploadType;
import com.recoverpro.server.repository.AssignmentRepository;
import com.recoverpro.server.repository.ColumnSchemaRepository;
import com.recoverpro.server.repository.FileUploadRepository;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.service.OnboardingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TASK 28.3.a/b: every step is checked live against its own backing table, not a maintained
 * onboarding-progress row -- same "live check, correct by construction" reasoning as
 * {@code EntitlementServiceImpl}'s class javadoc (a separately-maintained flag drifts the moment
 * any write path forgets to set it; a live check can't drift). {@code teamInvited} counts more
 * than just the org's own initial admin -- {@code PlatformOrganizationController.create()} always
 * creates that one user, so requiring only "at least one user" would make the step permanently
 * true from the moment of org creation, never actually reflecting whether the org invited anyone.
 */
@Service
@RequiredArgsConstructor
public class OnboardingServiceImpl implements OnboardingService {

    private static final List<FileUploadStatus> COMPLETED_STATUSES =
            List.of(FileUploadStatus.COMPLETED, FileUploadStatus.PARTIALLY_COMPLETED);

    private final UserRepository userRepository;
    private final ColumnSchemaRepository columnSchemaRepository;
    private final FileUploadRepository fileUploadRepository;
    private final AssignmentRepository assignmentRepository;
    private final OrganizationRepository organizationRepository;

    @Override
    @Transactional(readOnly = true)
    public OnboardingChecklistResponse getChecklist(UUID organizationId) {
        boolean teamInvited = userRepository.countByOrganizationId(organizationId) > 1;
        boolean columnSchemaConfigured = columnSchemaRepository.existsByOrganizationId(organizationId);
        Optional<FileUpload> firstImport = fileUploadRepository.findFirstCompletedByOrganizationIdAndUploadType(
                organizationId, UploadType.ALLOCATION, COMPLETED_STATUSES);
        boolean firstAssignmentRun = assignmentRepository.existsByOrganizationIdAndIsDeletedFalse(organizationId);

        Long minutesToFirstImport = firstImport
                .flatMap(upload -> organizationRepository.findById(organizationId)
                        .map(org -> Duration.between(org.getCreatedAt(), upload.getCreatedAt()).toMinutes()))
                .orElse(null);

        return OnboardingChecklistResponse.builder()
                .teamInvited(teamInvited)
                .columnSchemaConfigured(columnSchemaConfigured)
                .firstFileImported(firstImport.isPresent())
                .firstAssignmentRun(firstAssignmentRun)
                .allComplete(teamInvited && columnSchemaConfigured && firstImport.isPresent() && firstAssignmentRun)
                .timeToFirstImportMinutes(minutesToFirstImport)
                .build();
    }
}
