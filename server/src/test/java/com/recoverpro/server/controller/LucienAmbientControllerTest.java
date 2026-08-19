package com.recoverpro.server.controller;

import com.recoverpro.server.client.SttClient;
import com.recoverpro.server.client.TtsClient;
import com.recoverpro.server.dto.request.AmbientTurnRequest;
import com.recoverpro.server.dto.response.AmbientTurnResponse;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.LucienService;
import com.recoverpro.server.service.VisitInterviewService;
import com.recoverpro.server.service.ai.ChatRateLimiter;
import com.recoverpro.server.service.ai.TranslationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LucienAmbientControllerTest {

    @Mock private LucienService lucienService;
    @Mock private VisitInterviewService visitInterviewService;
    @Mock private TtsClient ttsClient;
    @Mock private SttClient sttClient;
    @Mock private TranslationService translationService;
    @Mock private ChatRateLimiter chatRateLimiter;

    private LucienController controller;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        controller = new LucienController(
                lucienService, visitInterviewService, ttsClient, sttClient, translationService, chatRateLimiter);
        User user = User.builder().id(UUID.randomUUID()).organizationId(UUID.randomUUID()).build();
        principal = new UserPrincipal(user);
    }

    @Test
    void ambientTurn_delegatesToServiceWithForceSpeakFalse() {
        AmbientTurnRequest request = AmbientTurnRequest.builder().text("hello").build();
        AmbientTurnResponse expected = AmbientTurnResponse.builder().speak(false).text(null).build();
        when(lucienService.ambientTurn(eq("sess-1"), eq(request), eq(false), eq(principal)))
                .thenReturn(expected);

        var response = controller.ambientTurn("sess-1", request, principal);

        assertThat(response.getBody().getData()).isEqualTo(expected);
    }

    @Test
    void help_delegatesToServiceWithForceSpeakTrue() {
        AmbientTurnRequest request = AmbientTurnRequest.builder().text("silence").build();
        AmbientTurnResponse expected = AmbientTurnResponse.builder()
                .speak(true).text("Try a payment plan.").build();
        when(lucienService.ambientTurn(eq("sess-2"), eq(request), eq(true), eq(principal)))
                .thenReturn(expected);

        var response = controller.help("sess-2", request, principal);

        assertThat(response.getBody().getData()).isEqualTo(expected);
        ArgumentCaptor<Boolean> forceSpeakCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(lucienService).ambientTurn(eq("sess-2"), eq(request), forceSpeakCaptor.capture(), eq(principal));
        assertThat(forceSpeakCaptor.getValue()).isTrue();
    }
}
