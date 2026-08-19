package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.dto.request.OptimizeAssignmentOrderRequest;
import com.recoverpro.server.dto.response.OptimizedAssignmentOrderResponse;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.service.AllocationOptimizerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/assignments")
@RequiredArgsConstructor
public class AllocationOptimizerController {

    private final AllocationOptimizerService allocationOptimizerService;

    @PostMapping("/optimize-order")
    @PreAuthorize(Authz.ADMINS)
    public ResponseEntity<ApiResponse<OptimizedAssignmentOrderResponse>> optimize(
            @Valid @RequestBody OptimizeAssignmentOrderRequest request) {
        log.info("POST /api/v1/assignments/optimize-order - allocations={}, agent={}",
                request.getAllocationIds() != null ? request.getAllocationIds().size() : 0,
                request.getAgentId());
        return ResponseEntity.ok(ApiResponse.success(allocationOptimizerService.optimize(request)));
    }
}
