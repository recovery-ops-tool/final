package com.recoverpro.server.controller;

import com.recoverpro.server.common.SafeSort;
import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.dto.request.AttendanceCheckInRequest;
import com.recoverpro.server.dto.response.AttendanceResponse;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AttendanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// SYSTEM 09 TASK 9.2: makes the pre-existing filter-chain authentication requirement explicit at
// the method-security layer -- checkIn/getMyAttendance are self-service (principal.getId()
// throughout); getByOrgAndDate keeps its own more specific @PreAuthorize below, which takes
// precedence over this class-level one for that method.
@RestController
@RequestMapping("/api/v1/attendance")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AttendanceController {

    private final AttendanceService attendanceService;

    // TASK 26.2: Pageable's Sort is bound directly from the client's own ?sort= query param by
    // Spring's PageableHandlerMethodArgumentResolver, with no allowlisting of its own -- sanitized
    // via SafeSort.sanitize() below before it reaches attendanceRepository.findByOrgIdAndAttendanceDate.
    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "attendanceDate", "attendanceDate",
            "checkedInAt", "checkedInAt",
            "createdAt", "createdAt");

    @PostMapping("/check-in")
    public ResponseEntity<ApiResponse<AttendanceResponse>> checkIn(
            @Valid @RequestBody(required = false) AttendanceCheckInRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (request == null) request = new AttendanceCheckInRequest();
        AttendanceResponse response = attendanceService.checkIn(
                principal.getId(), principal.getOrganizationId(), request);
        HttpStatus status = response.isAlreadyRecorded() ? HttpStatus.OK : HttpStatus.CREATED;
        String message = response.isAlreadyRecorded()
                ? "Attendance already recorded for today" : "Attendance recorded";
        return ResponseEntity.status(status).body(ApiResponse.of(message, response));
    }

    @GetMapping
    @PreAuthorize(Authz.LEADS)
    public ResponseEntity<ApiResponse<PagedResponse<AttendanceResponse>>> getByOrgAndDate(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @PageableDefault(size = 20) Pageable pageable) {
        pageable = SafeSort.sanitize(pageable, SORTABLE_FIELDS, "attendanceDate", Sort.Direction.DESC);
        LocalDate queryDate = date != null ? date : LocalDate.now();
        Page<AttendanceResponse> page = attendanceService.getByOrgAndDate(
                principal.getOrganizationId(), queryDate, pageable);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(page)));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<List<AttendanceResponse>>> getMyAttendance(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(29);
        if (start.isBefore(end.minusDays(29))) start = end.minusDays(29);
        return ResponseEntity.ok(ApiResponse.success(
                attendanceService.getMyAttendance(principal.getId(), start, end)));
    }
}
