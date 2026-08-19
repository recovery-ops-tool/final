package com.recoverpro.server.security;

import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditActorType;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.enums.AuditResult;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM 09 TASK 9.4: this is the single call-through point for both denial audit paths, so its
 * tests carry the acceptance evidence for 9.4.a (ACCESS_DENIED records the attempted resource),
 * 9.4.b (401 and 403 are distinguishable -- different AuditAction values, different actor types),
 * and 9.4.c (the throttle gate is actually consulted, and a broken audit write never surfaces).
 */
@ExtendWith(MockitoExtension.class)
class AccessDenialAuditorTest {

    @Mock private AuditService auditService;
    @Mock private DenialAuditThrottle denialAuditThrottle;

    private AccessDenialAuditor auditor;

    private AccessDenialAuditor freshAuditor() {
        return new AccessDenialAuditor(auditService, denialAuditThrottle);
    }

    @Test
    void recordAccessDenied_recordsAccessDeniedWithAttemptedResourceAndDeniedResult() {
        auditor = freshAuditor();
        when(denialAuditThrottle.shouldRecord(anyString(), anyString())).thenReturn(true);
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/borrowers/123");

        auditor.recordAccessDenied(request, userId);

        ArgumentCaptor<AuditEventRequest> captor = ArgumentCaptor.forClass(AuditEventRequest.class);
        verify(auditService).record(captor.capture());
        AuditEventRequest recorded = captor.getValue();
        assertThat(recorded.getAction()).isEqualTo(AuditAction.ACCESS_DENIED);
        assertThat(recorded.getResourceType()).isEqualTo(AuditResourceType.USER);
        assertThat(recorded.getResourceId()).isEqualTo(userId.toString());
        assertThat(recorded.getResult()).isEqualTo(AuditResult.DENIED);
        assertThat(recorded.getMetadata())
                .containsEntry("path", "/api/v1/borrowers/123")
                .containsEntry("method", "DELETE");
    }

    @Test
    void recordUnauthorized_recordsAuthUnauthorizedWithAnonymousActor() {
        auditor = freshAuditor();
        when(denialAuditThrottle.shouldRecord(anyString(), anyString())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/borrowers");

        auditor.recordUnauthorized(request, "no valid authentication");

        ArgumentCaptor<AuditEventRequest> captor = ArgumentCaptor.forClass(AuditEventRequest.class);
        verify(auditService).record(captor.capture());
        AuditEventRequest recorded = captor.getValue();
        assertThat(recorded.getActorTypeOverride()).isEqualTo(AuditActorType.ANONYMOUS);
        assertThat(recorded.getMetadata()).containsEntry("reason", "no valid authentication");
    }

    /** 9.4.b: the literal reason two denials on the same endpoint are distinguishable is that
     *  they carry different AuditAction values -- this pins that down directly, not just via each
     *  method's own assertion, so a future edit that accidentally collapses them onto one action
     *  fails a test that says exactly why that's wrong. */
    @Test
    void accessDeniedAndUnauthorized_useDifferentAuditActions() {
        auditor = freshAuditor();
        when(denialAuditThrottle.shouldRecord(anyString(), anyString())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/borrowers");

        auditor.recordAccessDenied(request, UUID.randomUUID());
        auditor.recordUnauthorized(request, "no valid authentication");

        ArgumentCaptor<AuditEventRequest> captor = ArgumentCaptor.forClass(AuditEventRequest.class);
        verify(auditService, org.mockito.Mockito.times(2)).record(captor.capture());
        assertThat(captor.getAllValues().get(0).getAction()).isEqualTo(AuditAction.ACCESS_DENIED);
        assertThat(captor.getAllValues().get(1).getAction()).isEqualTo(AuditAction.AUTH_UNAUTHORIZED);
    }

    @Test
    void throttleSuppresses_noAuditWriteHappens() {
        auditor = freshAuditor();
        when(denialAuditThrottle.shouldRecord(anyString(), anyString())).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/borrowers");

        auditor.recordAccessDenied(request, UUID.randomUUID());

        verify(auditService, never()).record(any());
    }

    @Test
    void auditServiceThrows_doesNotPropagate() {
        auditor = freshAuditor();
        when(denialAuditThrottle.shouldRecord(anyString(), anyString())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("DB unavailable")).when(auditService).record(any());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/borrowers");

        assertThatCode(() -> auditor.recordAccessDenied(request, UUID.randomUUID()))
                .as("a broken audit write must never turn a clean 401/403 into a 500")
                .doesNotThrowAnyException();
    }
}
