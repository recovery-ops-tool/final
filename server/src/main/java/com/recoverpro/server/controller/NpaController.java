package com.recoverpro.server.controller;

import com.recoverpro.server.dto.request.NpaFlagRequest;
import com.recoverpro.server.common.SafeSort;
import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.dto.response.NpaReportResponse;
import com.recoverpro.server.enums.NpaRiskLevel;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.NpaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports/npa")
@RequiredArgsConstructor
@Slf4j
public class NpaController {

    private static final String READERS = Authz.LEADS;
    private static final String ADMINS = Authz.ADMINS;

    private final NpaService npaService;

    @PreAuthorize(ADMINS)
    @PostMapping("/flag")
    public ResponseEntity<ApiResponse<NpaReportResponse>> flagNpa(
            @Valid @RequestBody NpaFlagRequest request) {
        log.info("POST /npa/flag - orgId={}, threshold={}d", request.getOrganizationId(), request.getOverdueThresholdDays());
        NpaReportResponse response = npaService.flagNpaRecords(request);
        return ResponseEntity.ok(ApiResponse.of("NPA records flagged", response));
    }

    @PreAuthorize(READERS)
    @GetMapping
    public ResponseEntity<ApiResponse<NpaReportResponse>> getNpaReport(
            @RequestParam UUID orgId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate reportDate = date != null ? date : LocalDate.now();
        return ResponseEntity.ok(ApiResponse.success(npaService.getNpaReport(orgId, reportDate)));
    }

    @PreAuthorize(READERS)
    @GetMapping("/records")
    public ResponseEntity<ApiResponse<PagedResponse<NpaReportResponse.NpaRecordResponse>>> getNpaRecords(
            @RequestParam UUID orgId,
            @RequestParam(required = false) NpaRiskLevel riskLevel,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<NpaReportResponse.NpaRecordResponse> result = npaService.getNpaRecords(
                orgId, riskLevel, PageRequest.of(page, size,
                        SafeSort.withIdTiebreaker(Sort.by("overdueDays").descending())));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }

    @PreAuthorize(READERS)
    @PatchMapping("/records/{id}/resolve")
    public ResponseEntity<ApiResponse<Void>> resolveNpa(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        npaService.resolveNpaRecord(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.of("NPA record resolved", null));
    }
}