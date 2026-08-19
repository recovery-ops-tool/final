package com.recoverpro.server.controller;

import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.dto.response.OnboardingChecklistResponse;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.OnboardingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnboardingControllerTest {

    @Mock private OnboardingService onboardingService;

    private OnboardingController controller() {
        return new OnboardingController(onboardingService);
    }

    @Test
    void getChecklist_noOrgContext_rejects() {
        UserPrincipal principal = mock(UserPrincipal.class);
        doReturn(null).when(principal).getOrganizationId();

        assertThatThrownBy(() -> controller().getChecklist(principal))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getChecklist_delegatesToServiceForCallersOwnOrg() {
        UUID orgId = UUID.randomUUID();
        UserPrincipal principal = mock(UserPrincipal.class);
        doReturn(orgId).when(principal).getOrganizationId();
        OnboardingChecklistResponse response = OnboardingChecklistResponse.builder().build();
        when(onboardingService.getChecklist(orgId)).thenReturn(response);

        var result = controller().getChecklist(principal);

        assertThat(result.getBody().getData()).isSameAs(response);
    }
}
