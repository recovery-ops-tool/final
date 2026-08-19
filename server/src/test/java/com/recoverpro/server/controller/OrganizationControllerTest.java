package com.recoverpro.server.controller;

import com.recoverpro.server.dto.request.UpdateOrganizationRequest;
import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SYSTEM 08 TASK 8.3.d: the org-admin MFA-required toggle on the existing self-service
 *  PATCH /organizations/me endpoint. */
@ExtendWith(MockitoExtension.class)
class OrganizationControllerTest {

    @Mock private OrganizationRepository orgRepo;
    @Mock private UserRepository userRepo;
    @Mock private AuditService auditService;

    private OrganizationController controller;
    private UUID orgId;
    private UserPrincipal caller;

    @BeforeEach
    void setUp() {
        controller = new OrganizationController(orgRepo, userRepo, auditService);
        orgId = UUID.randomUUID();
        caller = mock(UserPrincipal.class);
        lenient().when(caller.getOrganizationId()).thenReturn(orgId);
        lenient().when(caller.getId()).thenReturn(UUID.randomUUID());
        lenient().when(userRepo.countByOrganizationId(any())).thenReturn(1L);
        lenient().when(userRepo.findByOrganizationIdAndRoleName(any(), any())).thenReturn(java.util.List.of());
    }

    private Organization org(boolean mfaRequired) {
        return Organization.builder().id(orgId).name("Acme").code("ACME").mfaRequired(mfaRequired).build();
    }

    @Test
    void update_mfaRequiredTrue_setsFlagAndAudits() {
        Organization org = org(false);
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));

        UpdateOrganizationRequest request = new UpdateOrganizationRequest();
        request.setName("Acme");
        request.setMfaRequired(true);

        controller.update(caller, request);

        assertThat(org.isMfaRequired()).isTrue();
        verify(orgRepo).save(org);
        verify(auditService).record(argThat(evt ->
                evt.getAction() == com.recoverpro.server.enums.AuditAction.ORG_MFA_POLICY_CHANGED));
    }

    @Test
    void update_mfaRequiredNull_leavesExistingPolicyUnchanged() {
        Organization org = org(true);
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));

        UpdateOrganizationRequest request = new UpdateOrganizationRequest();
        request.setName("Acme"); // same name, mfaRequired left null

        controller.update(caller, request);

        assertThat(org.isMfaRequired()).isTrue();
        verify(auditService, never()).record(any());
    }

    @Test
    void update_mfaRequiredSameAsCurrent_doesNotDoubleAudit() {
        Organization org = org(true);
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));

        UpdateOrganizationRequest request = new UpdateOrganizationRequest();
        request.setName("Acme");
        request.setMfaRequired(true); // already true -- not a change

        controller.update(caller, request);

        verify(auditService, never()).record(any());
    }
}
