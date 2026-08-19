package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.config.PlatformConstants;
import com.recoverpro.server.dto.request.UpdateOrganizationRequest;
import com.recoverpro.server.dto.response.OrganizationSummaryResponse;
import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Organization Controller
 *
 * Tenant context comes strictly from JWT (no path-based org access).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationRepository orgRepo;
    private final UserRepository userRepo;
    private final AuditService auditService;

    /**
     * GET /api/v1/organizations/me
     */
    // SYSTEM 09 TASK 9.2: makes the pre-existing filter-chain authentication requirement
    // explicit -- self-service read of the caller's own org (requireOwnOrg below).
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<OrganizationSummaryResponse>> me(
            @AuthenticationPrincipal UserPrincipal principal) {

        Organization org = requireOwnOrg(principal);
        return ResponseEntity.ok(ApiResponse.success(toSummary(org)));
    }

    /**
     * PATCH /api/v1/organizations/me
     */
    @PatchMapping("/me")
    @PreAuthorize(Authz.ADMINS)
    @Transactional
    public ResponseEntity<ApiResponse<OrganizationSummaryResponse>> update(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateOrganizationRequest request) {

        Organization org = requireOwnOrg(principal);

        if (PlatformConstants.PLATFORM_ORG_CODE.equalsIgnoreCase(org.getCode())) {
            throw new BusinessException("The platform organization cannot be renamed");
        }

        if (!org.getName().equalsIgnoreCase(request.getName())) {
            if (orgRepo.existsByName(request.getName())) {
                throw new BusinessException("An organization with that name already exists");
            }
            org.setName(request.getName());
            log.info("Organization renamed | orgId={} newName={}", org.getId(), request.getName());
        }

        // SYSTEM 08 TASK 8.3.d: org-admin MFA-required toggle. Null means "leave unchanged" (see
        // UpdateOrganizationRequest's javadoc) -- only write and audit when the caller actually
        // sent a value AND it's actually changing.
        if (request.getMfaRequired() != null && request.getMfaRequired() != org.isMfaRequired()) {
            boolean previous = org.isMfaRequired();
            org.setMfaRequired(request.getMfaRequired());
            auditService.record(AuditEventRequest.builder()
                    .action(AuditAction.ORG_MFA_POLICY_CHANGED)
                    .resourceType(AuditResourceType.ORGANIZATION)
                    .resourceId(org.getId().toString())
                    .organizationIdOverride(org.getId())
                    .actorUserIdOverride(principal.getId())
                    .beforeState(java.util.Map.of("mfaRequired", String.valueOf(previous)))
                    .afterState(java.util.Map.of("mfaRequired", String.valueOf(org.isMfaRequired())))
                    .build());
            log.info("Organization MFA policy changed | orgId={} mfaRequired={} -> {}",
                    org.getId(), previous, org.isMfaRequired());
        }

        orgRepo.save(org);
        return ResponseEntity.ok(
                ApiResponse.of("Organization updated", toSummary(org))
        );
    }

    // ─────────────────────────────────────────────────────────

    private Organization requireOwnOrg(UserPrincipal principal) {
        UUID orgId = principal.getOrganizationId();

        if (orgId == null) {
            throw new BusinessException("Authenticated user has no organization context");
        }

        return orgRepo.findById(orgId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Organization not found: " + orgId));
    }

    private OrganizationSummaryResponse toSummary(Organization org) {

        long userCount = userRepo.countByOrganizationId(org.getId());

        String orgAdminEmail = userRepo
                .findByOrganizationIdAndRoleName(org.getId(), PlatformConstants.ROLE_ORG_ADMIN)
                .stream()
                .map(User::getEmail)
                .findFirst()
                .orElse(null);

        return OrganizationSummaryResponse.builder()
                .id(org.getId())
                .name(org.getName())
                .code(org.getCode())
                .orgType(org.getOrganizationType() != null ? org.getOrganizationType().name() : null)
                .isActive(org.isActive())
                .createdAt(org.getCreatedAt())
                .userCount(userCount)
                .orgAdminEmail(orgAdminEmail)
                .build();
    }
}