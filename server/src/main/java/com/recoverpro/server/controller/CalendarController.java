package com.recoverpro.server.controller;

import java.util.UUID;

import com.recoverpro.server.dto.request.CapacityConfigRequest;
import com.recoverpro.server.dto.request.HolidayRequest;
import com.recoverpro.server.common.SafeSort;
import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.entity.AgentCapacityConfig;
import com.recoverpro.server.entity.HolidayCalendar;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.service.CalendarService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/calendar")
@RequiredArgsConstructor
@Slf4j
public class CalendarController {

    private static final String READERS = Authz.LEADS;
    private static final String ADMINS = Authz.ADMINS;

    private final CalendarService calendarService;

    @PreAuthorize(ADMINS)
    @PostMapping("/holidays")
    public ResponseEntity<ApiResponse<HolidayCalendar>> addHoliday(
            @Valid @RequestBody HolidayRequest request) {
        HolidayCalendar holiday = calendarService.addHoliday(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("Holiday added", holiday));
    }

    @PreAuthorize(ADMINS)
    @DeleteMapping("/holidays/{id}")
    public ResponseEntity<ApiResponse<Void>> removeHoliday(@PathVariable UUID id) {
        calendarService.removeHoliday(id);
        return ResponseEntity.ok(ApiResponse.of("Holiday removed", null));
    }

    @PreAuthorize(READERS)
    @GetMapping("/holidays")
    public ResponseEntity<ApiResponse<PagedResponse<HolidayCalendar>>> getHolidays(
            @RequestParam UUID orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<HolidayCalendar> holidays = calendarService.getHolidays(orgId,
                PageRequest.of(page, size, SafeSort.withIdTiebreaker(Sort.by("holidayDate").ascending())));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(holidays)));
    }

    @PreAuthorize(READERS)
    @GetMapping("/check")
    public ResponseEntity<ApiResponse<Boolean>> checkAssignableDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam UUID orgId) {
        boolean assignable = calendarService.isAssignableDate(date, orgId);
        return ResponseEntity.ok(ApiResponse.success(assignable));
    }

    @PreAuthorize(ADMINS)
    @PutMapping("/config")
    public ResponseEntity<ApiResponse<AgentCapacityConfig>> upsertCapacityConfig(
            @Valid @RequestBody CapacityConfigRequest request) {
        AgentCapacityConfig config = calendarService.upsertCapacityConfig(request);
        return ResponseEntity.ok(ApiResponse.of("Capacity config updated", config));
    }

    @PreAuthorize(READERS)
    @GetMapping("/config")
    public ResponseEntity<ApiResponse<AgentCapacityConfig>> getCapacityConfig(
            @RequestParam UUID orgId) {
        return ResponseEntity.ok(ApiResponse.success(calendarService.getCapacityConfig(orgId)));
    }
}