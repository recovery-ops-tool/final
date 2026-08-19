package com.recoverpro.server.controller;

import com.recoverpro.server.AbstractIntegrationTest;
import com.recoverpro.server.entity.AuditEvent;
import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.repository.AuditEventRepository;
import com.recoverpro.server.security.RlsOrgIdHolder;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM 10 TASK 10.4: real end-to-end proof of the endpoint's own literal ACCEPTANCE line ("an
 * ORG_ADMIN sees only their org's events; exporting produces an AUDIT_LOG_EXPORTED row naming the
 * exporter") against the real database and real RLS policy, not a mocked query service -- SYSTEM
 * 19's platform_invoices RLS finding this same session is exactly the kind of gap a mock can't
 * catch.
 */
class AuditEventRlsIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AuditService auditService;
    @Autowired private AuditEventRepository auditEventRepository;

    @Test
    void orgAdmin_seesOnlyOwnOrgEvents_notAnotherOrgs() {
        Organization orgA = createOrg("AuditEvtA");
        Organization orgB = createOrg("AuditEvtB");
        User userA = createUser(orgA, "ROLE_ORG_ADMIN");
        User userB = createUser(orgB, "ROLE_ORG_ADMIN");

        String markerA = UUID.randomUUID().toString();
        String markerB = UUID.randomUUID().toString();
        writeEvent(userA, markerA);
        writeEvent(userB, markerB);

        String tokenA = jwtTokenProvider.generateAccessToken(new UserPrincipal(userA), userA.getId());
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl("/api/v1/audit-events?size=200"),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenA)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("RLS must scope the query to the caller's own org")
                .contains(markerA)
                .doesNotContain(markerB);
    }

    @Test
    void export_writesAuditLogExportedEvent_namingExporterAndRowCount() {
        Organization org = createOrg("AuditEvtExport");
        User exporter = createUser(org, "ROLE_ORG_ADMIN");
        String marker = UUID.randomUUID().toString();
        writeEvent(exporter, marker);

        String token = jwtTokenProvider.generateAccessToken(new UserPrincipal(exporter), exporter.getId());
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl("/api/v1/audit-events/export?format=json"),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(marker);

        actAsUser(exporter);
        List<AuditEvent> exportEvents = auditEventRepository.findAll().stream()
                .filter(e -> e.getAction() == AuditAction.AUDIT_LOG_EXPORTED)
                .toList();
        SecurityContextHolder.clearContext();
        RlsOrgIdHolder.clear();

        assertThat(exportEvents)
                .as("the export itself must be audited, naming the exporter")
                .hasSize(1);
        AuditEvent exportEvent = exportEvents.get(0);
        assertThat(exportEvent.getActorUserId()).isEqualTo(exporter.getId());
        assertThat(exportEvent.getResourceType()).isEqualTo(AuditResourceType.AUDIT_LOG);
        assertThat(exportEvent.getMetadata()).containsEntry("format", "json");
        // rowCount reflects what the export query saw BEFORE this AUDIT_LOG_EXPORTED row itself
        // was written -- just the earlier writeEvent() row. Compared as a Number, not a specific
        // boxed type -- Hibernate's JSONB round-trip doesn't guarantee Integer over Long/BigDecimal.
        Object rowCount = exportEvent.getMetadata().get("rowCount");
        assertThat(((Number) rowCount).intValue()).isEqualTo(1);
    }

    @Test
    void platformAdmin_withoutOrgId_isRejected() {
        String adminToken = tokenFor(null, com.recoverpro.server.config.PlatformConstants.ROLE_PLATFORM_ADMIN);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl("/api/v1/audit-events"),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(adminToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("a platform admin must name a target org and reason before reading any tenant's audit trail")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private void writeEvent(User user, String resourceIdMarker) {
        actAsUser(user);
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.USER_UPDATED)
                .resourceType(AuditResourceType.USER)
                .resourceId(resourceIdMarker)
                .build());
        SecurityContextHolder.clearContext();
        RlsOrgIdHolder.clear();
    }
}
