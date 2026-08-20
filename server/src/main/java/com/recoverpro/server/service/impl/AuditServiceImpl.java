package com.recoverpro.server.service.impl;

import com.recoverpro.server.config.RequestLoggingFilter;
import com.recoverpro.server.entity.AuditEvent;
import com.recoverpro.server.enums.AuditActorType;
import com.recoverpro.server.enums.AuditSource;
import com.recoverpro.server.repository.AuditEventRepository;
import com.recoverpro.server.security.RlsOrgIdHolder;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private final AuditEventRepository auditEventRepository;

    @Override
    @Transactional
    public void record(AuditEventRequest request) {
        UserPrincipal principal = currentPrincipal();

        // actorUserIdOverride/actorTypeOverride only apply when there's no live authenticated
        // principal (async/background work) -- an authenticated caller can never override its
        // own identity, which would otherwise let a live request spoof a different actor.
        AuditActorType actorType = principal != null
                ? AuditActorType.USER
                : (request.getActorTypeOverride() != null ? request.getActorTypeOverride() : AuditActorType.SYSTEM);

        java.util.UUID actorUserId = principal != null ? principal.getId() : request.getActorUserIdOverride();

        AuditEvent event = AuditEvent.builder()
                .organizationId(request.getOrganizationIdOverride() != null
                        ? request.getOrganizationIdOverride() : RlsOrgIdHolder.get())
                .actorUserId(actorUserId)
                .actorType(actorType)
                .actorRole(principal != null ? joinRoles(principal) : null)
                .effectiveUserId(actorUserId)
                .action(request.getAction())
                .resourceType(request.getResourceType())
                .resourceId(request.getResourceId())
                .severity(request.getAction().defaultSeverity())
                .result(request.getResult())
                .source(resolveSource(request, actorType))
                .requestId(MDC.get(RequestLoggingFilter.MDC_KEY))
                .correlationId(request.getCorrelationId())
                .ipAddress(currentRequest().map(HttpServletRequest::getRemoteAddr).orElse(null))
                .userAgent(currentRequest().map(r -> r.getHeader("User-Agent")).orElse(null))
                .reason(request.getReason())
                .beforeState(request.getBeforeState())
                .afterState(request.getAfterState())
                .metadata(request.getMetadata())
                .build();

        auditEventRepository.save(event);
        log.debug("Audited: action={} resourceType={} resourceId={} result={}",
                event.getAction(), event.getResourceType(), event.getResourceId(), event.getResult());
    }

    private AuditSource resolveSource(AuditEventRequest request, AuditActorType actorType) {
        if (request.getSourceOverride() != null) return request.getSourceOverride();
        if (actorType == AuditActorType.BACKGROUND_JOB) return AuditSource.BACKGROUND_JOB;
        if (actorType == AuditActorType.SYSTEM) return AuditSource.SYSTEM;
        return currentRequest().isPresent() ? AuditSource.WEB : AuditSource.SYSTEM;
    }

    private UserPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UserPrincipal up)) {
            return null;
        }
        return up;
    }

    /**
     * SYSTEM 10 TASK 10.4 (found while writing a real integration test, not a mock): this used to
     * join EVERY granted authority -- role names AND every permission name each role carries
     * ({@code UserPrincipal#buildAuthorities}) -- into {@code actor_role VARCHAR(100)}. That
     * column's own javadoc says it's "a snapshot of the role name," but permission names aren't
     * ROLE_-prefixed in this schema (confirmed against the real seed data: e.g. {@code CASE_ASSIGN},
     * not a role), so the unfiltered join massively overshot 100 chars for any role with a
     * meaningful permission set -- FO alone is 137 chars, ORG_ADMIN 329, PLATFORM_ADMIN 456.
     * EVERY audited action by an FO/TL/MANAGER/ORG_ADMIN/PLATFORM_ADMIN principal (i.e. almost all
     * of them) was throwing {@code DataException: value too long for type character varying(100)}
     * on the INSERT -- and since {@link #record} runs in its own REQUIRES_NEW transaction with no
     * caller catching the exception (confirmed: none of the ~20 call sites do), that failure
     * propagated all the way to a 500 response for whatever the caller was actually trying to do
     * (login, an RBAC change, a platform-admin action, ...), not just a lost audit row. Filtering
     * to ROLE_-prefixed authorities restores the field's actual documented contract and, as a side
     * effect, fixes the overflow for every realistic role/permission combination.
     */
    private static String joinRoles(UserPrincipal principal) {
        String roles = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .collect(Collectors.joining(","));
        return roles.isEmpty() ? null : roles;
    }

    private java.util.Optional<HttpServletRequest> currentRequest() {
        try {
            var attrs = RequestContextHolder.currentRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                return java.util.Optional.of(sra.getRequest());
            }
        } catch (IllegalStateException e) {
            // No request bound to this thread -- expected for async/background work.
        }
        return java.util.Optional.empty();
    }
}
