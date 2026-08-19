package com.recoverpro.server.controller;

import com.recoverpro.server.common.SafeSort;
import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.RateLimitExceededException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.dto.response.FileProcessingErrorResponse;
import com.recoverpro.server.dto.response.FileUploadResponse;
import com.recoverpro.server.enums.UploadType;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.FileUploadService;
import com.recoverpro.server.service.importer.ImportTemplateService;
import com.recoverpro.server.util.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/file-uploads")
@RequiredArgsConstructor
public class FileUploadController {

    private static final String READERS = Authz.LEADS;
    private static final String WRITERS = Authz.ADMINS;

    private final FileUploadService fileUploadService;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;
    private final ImportTemplateService importTemplateService;
    private final RateLimiter rateLimiter;
    private final AppProperties appProperties;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(WRITERS)
    public ResponseEntity<ApiResponse<FileUploadResponse>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "ALLOCATION") UploadType uploadType,
            @RequestParam(defaultValue = "false") boolean historicalImport,
            @AuthenticationPrincipal UserPrincipal principal) {

        // SYSTEM 07 TASK 7.3: keyed by the authenticated user, not IP -- each upload triggers
        // real background work (parsing, PII encryption, DB writes), so the identity that
        // matters is who queued it, the same reasoning ChatRateLimiter uses for agentId.
        AppProperties.Security sec = appProperties.getSecurity();
        String rateLimitKey = "upload:" + principal.getId();
        if (!rateLimiter.isAllowed(rateLimitKey, sec.getFileUploadMaxAttempts(), sec.getFileUploadWindowMinutes())) {
            long retryAfter = rateLimiter.getRetryAfterSeconds(rateLimitKey);
            throw new RateLimitExceededException(
                    "Too many uploads. Try again in " + retryAfter + "s.", retryAfter);
        }

        UUID effectiveOrgId = resolveOrgId(principal, organizationId, reason, "file-uploads:create");
        if (effectiveOrgId == null) throw new BusinessException("Authenticated user has no organization context");
        log.info("POST /api/v1/file-uploads - filename: {}, orgId: {}, userId: {}, type: {}, historical: {}",
                file.getOriginalFilename(), effectiveOrgId, principal.getId(), uploadType, historicalImport);
        FileUploadResponse response = fileUploadService.initiateUpload(
                file, effectiveOrgId, principal.getId(), uploadType, historicalImport);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.of("File upload initiated. Processing in background.", response));
    }

    /**
     * The canonical column layout for an upload type. Generated from the processors themselves,
     * so it cannot drift from what the parser actually accepts.
     */
    @GetMapping(value = "/template", produces = "text/csv")
    @PreAuthorize(READERS)
    public ResponseEntity<String> downloadTemplate(@RequestParam UploadType uploadType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + importTemplateService.filename(uploadType) + "\"")
                .body(importTemplateService.buildCsv(uploadType));
    }

    @GetMapping("/{id}/status")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<FileUploadResponse>> getUploadStatus(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        FileUploadResponse response = fileUploadService.getUploadStatus(id);
        assertSameTenant(response.getOrganizationId(), principal);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<PagedResponse<FileUploadResponse>>> getUploadsByOrganization(
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID effectiveOrgId = resolveOrgId(principal, organizationId, reason, "file-uploads:list");
        Pageable pageable = PageRequest.of(page, size,
                SafeSort.withIdTiebreaker(Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(ApiResponse.success(
                fileUploadService.getUploadsByOrganization(effectiveOrgId, pageable)));
    }

    @GetMapping("/{id}/errors")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<PagedResponse<FileProcessingErrorResponse>>> getProcessingErrors(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        FileUploadResponse upload = fileUploadService.getUploadStatus(id);
        assertSameTenant(upload.getOrganizationId(), principal);
        Pageable pageable = PageRequest.of(page, size,
                SafeSort.withIdTiebreaker(Sort.by(Sort.Direction.ASC, "rowNumber")));
        return ResponseEntity.ok(ApiResponse.success(fileUploadService.getProcessingErrors(id, pageable)));
    }

    /** Every processing error for this upload in one file, unlike the paginated JSON endpoint above. */
    @GetMapping(value = "/{id}/errors/download", produces = "text/csv")
    @PreAuthorize(READERS)
    public ResponseEntity<String> downloadProcessingErrors(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        FileUploadResponse upload = fileUploadService.getUploadStatus(id);
        assertSameTenant(upload.getOrganizationId(), principal);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"file-upload-" + id + "-errors.csv\"")
                .body(fileUploadService.buildProcessingErrorsCsv(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITERS)
    public ResponseEntity<ApiResponse<Void>> deleteFileUpload(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        FileUploadResponse upload = fileUploadService.getUploadStatus(id);
        assertSameTenant(upload.getOrganizationId(), principal);
        fileUploadService.softDeleteFileUpload(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.of("File upload and all associated allocations deleted", null));
    }

    private boolean isPlatformAdmin(UserPrincipal p) {
        return p.getAuthorities().stream().anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    private UUID resolveOrgId(UserPrincipal p, UUID requested, String reason, String resource) {
        if (isPlatformAdmin(p)) {
            if (requested != null && !requested.equals(p.getOrganizationId())) {
                // Platform admin explicitly acting on another org's data: RLS's current_org_id()
                // is always the platform admin's own org (never a real tenant's), so a normal
                // session would see zero rows here regardless of `requested`. Elevating through
                // the guard records who/whose/why before app.is_platform_admin is set, which the
                // file_uploads RLS policy (V050) opts into; the WHERE organization_id = requested
                // filter in fileUploadService below remains the actual scoping mechanism.
                platformAdminAccessGuard.beginCrossOrgAccess(p.getId(), requested, reason, resource);
            }
            return requested != null ? requested : p.getOrganizationId();
        }
        return p.getOrganizationId();
    }

    private void assertSameTenant(UUID resourceOrg, UserPrincipal principal) {
        if (isPlatformAdmin(principal)) return;
        if (resourceOrg == null || !resourceOrg.equals(principal.getOrganizationId())) {
            throw new ResourceNotFoundException("FileUpload not found");
        }
    }
}
