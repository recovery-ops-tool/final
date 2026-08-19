package com.recoverpro.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.dto.request.AuditEventFilterRequest;
import com.recoverpro.server.dto.response.AuditEventResponse;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AuditEventQueryService;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM 10 TASK 10.4.b/c: an ORG_ADMIN must never get an app-level org predicate (RLS alone
 * scopes them, per AuditEventSpecification's own javadoc); a platform admin must name an org and a
 * reason before seeing anything, matching ReportingController/VisitSessionController's established
 * elevation pattern; exporting must always write its own AUDIT_LOG_EXPORTED event.
 */
@ExtendWith(MockitoExtension.class)
class AuditEventControllerTest {

    @Mock private AuditEventQueryService auditEventQueryService;
    @Mock private AuditService auditService;
    @Mock private PlatformAdminAccessGuard platformAdminAccessGuard;

    private AuditEventController newController() {
        return new AuditEventController(auditEventQueryService, auditService, platformAdminAccessGuard, new ObjectMapper());
    }

    private UserPrincipal principalWithRole(String role, UUID orgId) {
        UserPrincipal p = mock(UserPrincipal.class);
        lenient().doReturn(UUID.randomUUID()).when(p).getId();
        lenient().doReturn(orgId).when(p).getOrganizationId();
        doReturn(List.of(new SimpleGrantedAuthority(role))).when(p).getAuthorities();
        return p;
    }

    @Test
    void search_platformAdmin_noOrgId_throwsClearBusinessException() {
        AuditEventController controller = newController();
        UserPrincipal admin = principalWithRole("ROLE_PLATFORM_ADMIN", null);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                controller.search(admin, new AuditEventFilterRequest(), null, null, null, null, 0, 20));
        assertThat(ex.getMessage()).contains("must specify");

        verify(auditEventQueryService, never()).search(any(), any(), any());
    }

    @Test
    void search_platformAdmin_withOrgIdAndReason_elevatesAndScopesQuery() {
        AuditEventController controller = newController();
        UUID targetOrg = UUID.randomUUID();
        UserPrincipal admin = principalWithRole("ROLE_PLATFORM_ADMIN", null);
        when(auditEventQueryService.search(any(), eq(targetOrg), any()))
                .thenReturn(new PageImpl<>(List.of()));

        controller.search(admin, new AuditEventFilterRequest(), null, null, targetOrg, "ticket #9", 0, 20);

        verify(platformAdminAccessGuard).beginCrossOrgAccess(
                eq(admin.getId()), eq(targetOrg), eq("ticket #9"), eq("audit-events:list"));
        verify(auditEventQueryService).search(any(), eq(targetOrg), any());
    }

    @Test
    void search_orgAdmin_neverElevates_andPassesNullOrgOverride() {
        AuditEventController controller = newController();
        UUID ownOrg = UUID.randomUUID();
        UserPrincipal orgAdmin = principalWithRole("ROLE_ORG_ADMIN", ownOrg);
        when(auditEventQueryService.search(any(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        controller.search(orgAdmin, new AuditEventFilterRequest(), null, null, null, null, 0, 20);

        verify(platformAdminAccessGuard, never()).beginCrossOrgAccess(any(), any(), any(), any());
        // Confirms no app-level org predicate is added for an org-scoped caller -- RLS alone scopes it.
        verify(auditEventQueryService).search(any(), isNull(), any());
    }

    @Test
    void export_writesAuditLogExportedEvent_namingFormatAndRowCount() {
        AuditEventController controller = newController();
        UUID ownOrg = UUID.randomUUID();
        UserPrincipal orgAdmin = principalWithRole("ROLE_ORG_ADMIN", ownOrg);
        AuditEventResponse row = AuditEventResponse.builder().id(UUID.randomUUID()).build();
        Page<AuditEventResponse> page = new PageImpl<>(List.of(row));
        when(auditEventQueryService.search(any(), isNull(), eq(Pageable.unpaged()))).thenReturn(page);

        controller.export(orgAdmin, new AuditEventFilterRequest(), null, null, null, null, "csv");

        ArgumentCaptor<AuditEventRequest> captor = ArgumentCaptor.forClass(AuditEventRequest.class);
        verify(auditService).record(captor.capture());
        AuditEventRequest recorded = captor.getValue();
        assertThat(recorded.getAction()).isEqualTo(AuditAction.AUDIT_LOG_EXPORTED);
        assertThat(recorded.getResourceType()).isEqualTo(AuditResourceType.AUDIT_LOG);
        assertThat(recorded.getMetadata()).containsEntry("format", "csv").containsEntry("rowCount", 1);
        // ORG_ADMIN path: no elevation happened, so the export event should NOT be forced onto a
        // different org -- organizationIdOverride null lets AuditServiceImpl fall back to
        // RlsOrgIdHolder.get(), which already resolves to the caller's own org for a real request.
        assertThat(recorded.getOrganizationIdOverride()).isNull();
    }

    @Test
    void export_platformAdmin_organizationIdOverrideNamesTargetOrg() {
        AuditEventController controller = newController();
        UUID targetOrg = UUID.randomUUID();
        UserPrincipal admin = principalWithRole("ROLE_PLATFORM_ADMIN", null);
        when(auditEventQueryService.search(any(), eq(targetOrg), eq(Pageable.unpaged())))
                .thenReturn(new PageImpl<>(List.of()));

        controller.export(admin, new AuditEventFilterRequest(), null, null, targetOrg, "ticket #9", "json");

        ArgumentCaptor<AuditEventRequest> captor = ArgumentCaptor.forClass(AuditEventRequest.class);
        verify(auditService).record(captor.capture());
        assertThat(captor.getValue().getOrganizationIdOverride()).isEqualTo(targetOrg);
    }
}
