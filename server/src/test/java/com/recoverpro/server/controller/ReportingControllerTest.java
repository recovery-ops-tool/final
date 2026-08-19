package com.recoverpro.server.controller;

import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.dto.request.ReportRequest;
import com.recoverpro.server.dto.response.ReportJobResponse;
import com.recoverpro.server.dto.response.TeamPerformanceResponse;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.MisEodReportService;
import com.recoverpro.server.service.ReportingService;
import com.recoverpro.server.util.RateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage: every endpoint on ReportingController took an explicit orgId but had no
 * app-layer check that it matched the caller's own org for MANAGER/TL/ORG_ADMIN, and every table
 * these reports read from had fail-closed RLS with no platform-admin bypass (V063) -- meaning every
 * report silently returned empty/zero-value data for a platform admin, and a confusing empty report
 * (instead of a clean 404) for a regular user targeting a foreign org. Covers a representative
 * sample of the 14 endpoints (query-param report, path-param report, and the POST enqueue path) --
 * all 14 route through the same authorizeOrgAccess helper.
 */
@ExtendWith(MockitoExtension.class)
class ReportingControllerTest {

    @Mock private ReportingService reportingService;
    @Mock private MisEodReportService misEodReportService;
    @Mock private PlatformAdminAccessGuard platformAdminAccessGuard;
    @Mock private RateLimiter rateLimiter;

    private ReportingController newController() {
        // SYSTEM 07 TASK 7.3: real AppProperties, not a mock -- the controller reads
        // getSecurity().getReportGenerateMaxAttempts()/WindowMinutes() off it, and its defaults
        // are sane values, no need to stub them per test.
        lenient().when(rateLimiter.isAllowed(anyString(), anyInt(), anyInt())).thenReturn(true);
        return new ReportingController(
                reportingService, misEodReportService, platformAdminAccessGuard, rateLimiter, new AppProperties());
    }

    private UserPrincipal principalWithRole(String role, UUID orgId) {
        UserPrincipal p = mock(UserPrincipal.class);
        lenient().doReturn(UUID.randomUUID()).when(p).getId();
        lenient().doReturn(orgId).when(p).getOrganizationId();
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role));
        lenient().doReturn(authorities).when(p).getAuthorities();
        return p;
    }

    @Test
    void getTeamPerformance_foreignOrg_throwsNotFound() {
        ReportingController controller = newController();
        UUID ownOrg = UUID.randomUUID();
        UUID foreignOrg = UUID.randomUUID();
        UserPrincipal manager = principalWithRole("ROLE_MANAGER", ownOrg);

        assertThrows(ResourceNotFoundException.class,
                () -> controller.getTeamPerformance(foreignOrg, null, null, null, manager));

        verify(reportingService, never()).getTeamPerformance(any(), any(), any());
    }

    @Test
    void getTeamPerformance_ownOrg_succeedsWithoutElevating() {
        ReportingController controller = newController();
        UUID ownOrg = UUID.randomUUID();
        UserPrincipal manager = principalWithRole("ROLE_MANAGER", ownOrg);
        when(reportingService.getTeamPerformance(eq(ownOrg), any(), any()))
                .thenReturn(TeamPerformanceResponse.builder().organizationId(ownOrg).build());

        controller.getTeamPerformance(ownOrg, null, null, null, manager);

        verify(platformAdminAccessGuard, never()).beginCrossOrgAccess(any(), any(), any(), any());
    }

    @Test
    void getTeamPerformance_platformAdmin_elevatesWithReason() {
        ReportingController controller = newController();
        UUID targetOrg = UUID.randomUUID();
        UserPrincipal admin = principalWithRole("ROLE_PLATFORM_ADMIN", null);
        when(reportingService.getTeamPerformance(eq(targetOrg), any(), any()))
                .thenReturn(TeamPerformanceResponse.builder().organizationId(targetOrg).build());

        controller.getTeamPerformance(targetOrg, null, null, "quarterly business review", admin);

        verify(platformAdminAccessGuard).beginCrossOrgAccess(
                eq(admin.getId()), eq(targetOrg), eq("quarterly business review"), eq("reports:teamPerformance"));
    }

    @Test
    void getJobStatus_foreignOrg_throwsNotFound() {
        ReportingController controller = newController();
        UUID ownOrg = UUID.randomUUID();
        UUID foreignOrg = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UserPrincipal orgAdmin = principalWithRole("ROLE_ORG_ADMIN", ownOrg);

        assertThrows(ResourceNotFoundException.class,
                () -> controller.getJobStatus(jobId, foreignOrg, null, orgAdmin));

        verify(reportingService, never()).getJobStatus(any(), any());
    }

    @Test
    void enqueueReport_foreignOrg_throwsNotFound() {
        ReportingController controller = newController();
        UUID ownOrg = UUID.randomUUID();
        UUID foreignOrg = UUID.randomUUID();
        UserPrincipal orgAdmin = principalWithRole("ROLE_ORG_ADMIN", ownOrg);
        ReportRequest request = new ReportRequest();
        request.setOrganizationId(foreignOrg);

        assertThrows(ResourceNotFoundException.class,
                () -> controller.enqueueReport(request, null, orgAdmin));

        verify(reportingService, never()).enqueueReport(any(), any());
    }

    @Test
    void enqueueReport_platformAdmin_elevatesWithReason() {
        ReportingController controller = newController();
        UUID targetOrg = UUID.randomUUID();
        UserPrincipal admin = principalWithRole("ROLE_PLATFORM_ADMIN", null);
        ReportRequest request = new ReportRequest();
        request.setOrganizationId(targetOrg);
        when(reportingService.enqueueReport(any(), any()))
                .thenReturn(ReportJobResponse.builder().id(UUID.randomUUID()).build());

        controller.enqueueReport(request, "support case #77", admin);

        verify(platformAdminAccessGuard).beginCrossOrgAccess(
                eq(admin.getId()), eq(targetOrg), eq("support case #77"), eq("reports:generate"));
    }

    @Test
    void getAgentPerformance_nullOrgId_skipsAuthorizationCheck() {
        ReportingController controller = newController();
        UUID agentId = UUID.randomUUID();
        UserPrincipal manager = principalWithRole("ROLE_MANAGER", UUID.randomUUID());

        controller.getAgentPerformance(agentId, null, null, null, manager);

        verify(platformAdminAccessGuard, never()).beginCrossOrgAccess(any(), any(), any(), any());
    }
}
