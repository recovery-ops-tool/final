package com.recoverpro.server.controller;

import com.recoverpro.server.common.SafeSort;
import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.entity.MessageTemplate;
import com.recoverpro.server.enums.Channel;
import com.recoverpro.server.enums.MessageTemplateStatus;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.MessageTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Maker-checker template activation endpoints (design-doc §4.6).
 *
 * Templates are seeded by a maker (FIELD_AGENT or admin) directly into
 * the {@code message_templates} table; activation requires a different
 * checker via these endpoints. Surface deliberately admin-only -- the
 * security implication of an attacker-controlled template fanned out to
 * every borrower is too high to leave on a lower-trust role.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/message-templates")
@RequiredArgsConstructor
public class MessageTemplateController {

    private static final String READERS = Authz.LEADS;
    private static final String ADMINS = Authz.ADMINS;

    private final MessageTemplateService messageTemplateService;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;

    @GetMapping
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<PagedResponse<MessageTemplate>>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) MessageTemplateStatus status,
            @RequestParam(required = false) Channel channel,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID effectiveOrgId = resolveOrgId(principal, organizationId, reason, "message-templates:list");
        if (effectiveOrgId == null) throw new BusinessException("Authenticated user has no organization context");

        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(
                messageTemplateService.findAll(effectiveOrgId, status, channel,
                        PageRequest.of(page, size,
                                SafeSort.withIdTiebreaker(Sort.by(Sort.Direction.ASC, "templateKey")))))));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<MessageTemplate>> getById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) String reason) {

        UUID effectiveOrgId = resolveOrgId(principal, organizationId, reason, "message-templates:get");
        if (effectiveOrgId == null) throw new BusinessException("Authenticated user has no organization context");

        return ResponseEntity.ok(ApiResponse.success(
                messageTemplateService.findById(effectiveOrgId, id)));
    }

    private boolean isPlatformAdmin(UserPrincipal p) {
        return p.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    private UUID resolveOrgId(UserPrincipal p, UUID requested, String reason, String resource) {
        if (isPlatformAdmin(p)) {
            if (requested != null && !requested.equals(p.getOrganizationId())) {
                // Same RLS gap BCR-12 found on file_uploads: current_org_id() is always the
                // platform admin's own org, never the tenant org being asked about here.
                platformAdminAccessGuard.beginCrossOrgAccess(p.getId(), requested, reason, resource);
            }
            return requested != null ? requested : p.getOrganizationId();
        }
        return p.getOrganizationId();
    }

    @PostMapping("/{id}/submit-for-dlt")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<MessageTemplate>> submitForDlt(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.of(
                "Template submitted for DLT review",
                messageTemplateService.submitForDlt(id, principal == null ? null : principal.getId())));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<MessageTemplate>> activate(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.of(
                "Template activated",
                messageTemplateService.activate(id, principal == null ? null : principal.getId())));
    }

    @PostMapping("/{id}/retire")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<MessageTemplate>> retire(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.of(
                "Template retired",
                messageTemplateService.retire(id, principal == null ? null : principal.getId())));
    }
}