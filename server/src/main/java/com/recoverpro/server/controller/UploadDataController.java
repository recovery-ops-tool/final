package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.AddColumnRequest;
import com.recoverpro.server.dto.request.SaveRowRequest;
import com.recoverpro.server.dto.response.UploadDataResponse;
import com.recoverpro.server.dto.response.UploadRowResponse;
import com.recoverpro.server.entity.FileUpload;
import com.recoverpro.server.repository.FileUploadRepository;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.UploadDataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/file-uploads/{uploadId}")
@RequiredArgsConstructor
@PreAuthorize(Authz.LEADS)
public class UploadDataController {

    private final UploadDataService uploadDataService;
    private final FileUploadRepository fileUploadRepository;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;

    @GetMapping("/rows")
    public ResponseEntity<ApiResponse<UploadDataResponse>> getRows(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID uploadId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        assertOwnership(principal, uploadId);
        return ResponseEntity.ok(ApiResponse.success(uploadDataService.getRows(uploadId, page, size)));
    }

    @PostMapping("/rows")
    public ResponseEntity<ApiResponse<UploadRowResponse>> addRow(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID uploadId,
            @Valid @RequestBody SaveRowRequest request) {
        assertOwnership(principal, uploadId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(uploadDataService.addRow(uploadId, request.getData(), principal.getId())));
    }

    @PutMapping("/rows/{rowId}")
    public ResponseEntity<ApiResponse<UploadRowResponse>> updateRow(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID uploadId,
            @PathVariable UUID rowId,
            @Valid @RequestBody SaveRowRequest request) {
        assertOwnership(principal, uploadId);
        return ResponseEntity.ok(ApiResponse.success(uploadDataService.updateRow(uploadId, rowId, request.getData())));
    }

    @DeleteMapping("/rows/{rowId}")
    public ResponseEntity<ApiResponse<Void>> deleteRow(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID uploadId,
            @PathVariable UUID rowId) {
        assertOwnership(principal, uploadId);
        uploadDataService.deleteRow(uploadId, rowId, principal.getId());
        return ResponseEntity.ok(ApiResponse.of("Row deleted", null));
    }

    @PostMapping("/columns")
    public ResponseEntity<ApiResponse<Void>> addColumn(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID uploadId,
            @Valid @RequestBody AddColumnRequest request) {
        assertOwnership(principal, uploadId);
        uploadDataService.addColumn(uploadId, request.getColumnName(), request.getDefaultValue());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("Column '" + request.getColumnName() + "' added to all rows", null));
    }

    @DeleteMapping("/columns/{columnName}")
    public ResponseEntity<ApiResponse<Void>> deleteColumn(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID uploadId,
            @PathVariable String columnName) {
        assertOwnership(principal, uploadId);
        uploadDataService.deleteColumn(uploadId, columnName);
        return ResponseEntity.ok(ApiResponse.of("Column '" + columnName + "' removed from all rows", null));
    }

    private boolean isPlatformAdmin(UserPrincipal p) {
        return p.getAuthorities().stream().anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    /**
     * file_uploads' RLS policy (V050) and allocations' (V063, backing UploadDataServiceImpl's row
     * data) both already support a platform-admin bypass -- but the bypass only activates once
     * app.is_platform_admin is actually set for the request, which nothing in this controller ever
     * did. The early-return below looked correct but was dead: uploadDataService's underlying
     * fileUploadRepo/allocationRepo queries stayed RLS-scoped to the caller's own (null) org
     * regardless, same bug class as ptp_records/reconciliation_runs found earlier this pass.
     */
    private void assertOwnership(UserPrincipal principal, UUID uploadId) {
        if (isPlatformAdmin(principal)) {
            platformAdminAccessGuard.beginUnattendedCrossOrgAccess(principal.getId(), "uploadData:" + uploadId);
            return;
        }
        FileUpload up = fileUploadRepository.findByIdAndIsDeletedFalse(uploadId)
                .orElseThrow(() -> new ResourceNotFoundException("Upload not found: " + uploadId));
        UUID resourceOrg = up.getOrganization() != null ? up.getOrganization().getId() : null;
        if (resourceOrg == null || !resourceOrg.equals(principal.getOrganizationId())) {
            throw new ResourceNotFoundException("Upload not found: " + uploadId);
        }
    }
}
