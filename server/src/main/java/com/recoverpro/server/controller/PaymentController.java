package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.dto.request.CreatePaymentIntentRequest;
import com.recoverpro.server.dto.request.CreatePaymentLinkRequest;
import com.recoverpro.server.dto.response.PaymentIntentResponse;
import com.recoverpro.server.dto.response.PaymentLinkResponse;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.PaymentLinkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Payment orchestration endpoints (design-doc §5).
 *
 *   /api/v1/payments/intents -- auth'd, idempotent
 *   /api/v1/payments/links   -- auth'd
 *   /p/{token}               -- public link resolution
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentLinkService paymentLinkService;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;

    private static final String READERS = Authz.FO_AND_ADMINS;

    @PostMapping("/api/v1/payments/intents")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<PaymentIntentResponse>> createIntent(
            @Valid @RequestBody CreatePaymentIntentRequest request,
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.info("POST /api/v1/payments/intents - allocation={}, amount={}",
                request.getAllocationId(), request.getAmount());
        PaymentIntentResponse response = paymentLinkService.createIntent(
                request, principal.getId(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("Payment intent created", response));
    }

    @GetMapping("/api/v1/payments/intents/{id}")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<PaymentIntentResponse>> getIntent(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        // Tenant scoping is RLS's job here, not a post-fetch app-layer comparison: for a
        // non-platform-admin caller, findById is already filtered to their own org (V040), so a
        // foreign-org id comes back empty -> clean 404 -- no need to fetch it and compare after.
        // PLATFORM_ADMIN needs an explicit elevation first (V060 added the bypass clause, but it
        // only takes effect once app.is_platform_admin is set for this request); the target org
        // isn't knowable before the fetch for a bare-id lookup like this one, so this uses the
        // same unattended-elevation escape hatch as SosAudioWebSocketHandler rather than the
        // reason-collecting beginCrossOrgAccess, which needs the org known up front.
        boolean isPlatformAdmin = principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
        if (isPlatformAdmin) {
            platformAdminAccessGuard.beginUnattendedCrossOrgAccess(principal.getId(), "payment_intent:" + id);
        }
        PaymentIntentResponse intent = paymentLinkService.getIntent(id);
        return ResponseEntity.ok(ApiResponse.success(intent));
    }

    @PostMapping("/api/v1/payments/links")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<PaymentLinkResponse>> createLink(
            @Valid @RequestBody CreatePaymentLinkRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.info("POST /api/v1/payments/links - intent={}, rail={}",
                request.getIntentId(), request.getRail());
        PaymentLinkResponse response = paymentLinkService.createLink(request, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("Payment link issued", response));
    }

    // Public link resolution at GET /p/{token} now lives in
    // PaymentLinkResolveController -- returns HTML/302 to drive the
    // borrower into their UPI app rather than a JSON payload.
}