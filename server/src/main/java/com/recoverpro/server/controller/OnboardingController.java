package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.dto.response.OnboardingChecklistResponse;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.OnboardingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** TASK 28.3: guided-activation checklist for the caller's own org. */
@RestController
@RequestMapping("/api/v1/onboarding")
@RequiredArgsConstructor
@PreAuthorize(Authz.LEADS)
public class OnboardingController {

    private final OnboardingService onboardingService;

    @GetMapping("/checklist")
    public ResponseEntity<ApiResponse<OnboardingChecklistResponse>> getChecklist(
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID orgId = principal.getOrganizationId();
        if (orgId == null) throw new BusinessException("Caller has no organization context");
        return ResponseEntity.ok(ApiResponse.success(onboardingService.getChecklist(orgId)));
    }
}
