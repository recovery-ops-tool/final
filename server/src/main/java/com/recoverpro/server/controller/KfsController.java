package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.GenerateKfsRequest;
import com.recoverpro.server.dto.response.KeyFactStatementResponse;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.KfsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/kfs")
@RequiredArgsConstructor
public class KfsController {

    private static final String READERS = Authz.ALL_STAFF;

    private final KfsService kfsService;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;

    @PostMapping
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<KeyFactStatementResponse>> generate(
            @Valid @RequestBody GenerateKfsRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "kfs:generate:" + request.getRestructureProposalId());
        KeyFactStatementResponse response = kfsService.generate(request.getRestructureProposalId(), principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("Key Fact Statement generated", response));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<KeyFactStatementResponse>> getById(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "kfs:getById:" + id);
        KeyFactStatementResponse response = kfsService.getById(id);
        assertSameTenantOrg(response.getOrganizationId(), principal);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize(READERS)
    public ResponseEntity<byte[]> downloadPdf(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "kfs:downloadPdf:" + id);
        assertSameTenantOrg(kfsService.getById(id).getOrganizationId(), principal);
        byte[] pdf = kfsService.downloadPdf(id);

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("kfs_" + id + ".pdf")
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(pdf);
    }

    @GetMapping("/restructure-proposal/{restructureProposalId}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<KeyFactStatementResponse>> getByRestructureProposal(
            @PathVariable UUID restructureProposalId, @AuthenticationPrincipal UserPrincipal principal) {

        elevateIfPlatformAdmin(principal, "kfs:byRestructureProposal:" + restructureProposalId);
        KeyFactStatementResponse response = kfsService.getByRestructureProposalId(restructureProposalId);
        assertSameTenantOrg(response.getOrganizationId(), principal);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private void assertSameTenantOrg(UUID resourceOrg, UserPrincipal principal) {
        if (isPlatformAdmin(principal)) return;

        if (resourceOrg == null || !resourceOrg.equals(principal.getOrganizationId())) {
            throw new ResourceNotFoundException("KeyFactStatement not found");
        }
    }

    private static boolean isPlatformAdmin(UserPrincipal principal) {
        return principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    private void elevateIfPlatformAdmin(UserPrincipal principal, String resource) {
        if (isPlatformAdmin(principal)) {
            platformAdminAccessGuard.beginUnattendedCrossOrgAccess(principal.getId(), resource);
        }
    }
}
