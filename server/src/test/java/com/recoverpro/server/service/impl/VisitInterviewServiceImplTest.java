package com.recoverpro.server.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.ConfirmVisitActionRequest;
import com.recoverpro.server.dto.response.ChatResponse;
import com.recoverpro.server.entity.ChatMessage;
import com.recoverpro.server.entity.ChatSession;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.lucien.agent.ConfirmationService;
import com.recoverpro.server.lucien.agent.PendingAction;
import com.recoverpro.server.lucien.tool.ToolExecutor;
import com.recoverpro.server.repository.AgentStepRepository;
import com.recoverpro.server.repository.ChatMessageRepository;
import com.recoverpro.server.repository.ChatSessionRepository;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.safety.DataSanitizer;
import com.recoverpro.server.service.storage.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisitInterviewServiceImplTest {

    @Mock private ChatSessionRepository sessionRepository;
    @Mock private ChatMessageRepository messageRepository;
    @Mock private AgentStepRepository stepRepository;
    @Mock private ConfirmationService confirmationService;
    @Mock private ToolExecutor toolExecutor;
    @Mock private StoragePort storagePort;
    @Mock private DataSanitizer dataSanitizer;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private VisitInterviewServiceImpl service;

    private static final String SESSION_ID = "session-1";
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID ALLOCATION_ID = UUID.randomUUID();

    private ChatSession session;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        service = new VisitInterviewServiceImpl(sessionRepository, messageRepository, stepRepository,
                confirmationService, toolExecutor, storagePort, dataSanitizer, objectMapper);
        ReflectionTestUtils.setField(service, "storagePath", "./uploads/visits");
        ReflectionTestUtils.setField(service, "modelName", "llama3");

        session = ChatSession.builder().id(SESSION_ID).agentId(AGENT_ID).agentFirstName("Alex")
                .allocationId(ALLOCATION_ID).isActive(true).totalMessages(0).build();

        User user = User.builder().id(AGENT_ID).organizationId(UUID.randomUUID()).build();
        principal = new UserPrincipal(user);

        lenient().when(sessionRepository.findByIdAndIsActiveTrue(SESSION_ID)).thenReturn(Optional.of(session));
        lenient().when(messageRepository.save(any())).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
        lenient().when(dataSanitizer.stripPii(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    private PendingAction pendingAction(String toolName) {
        return PendingAction.builder()
                .actionId("action-1").sessionId(SESSION_ID).principalId(AGENT_ID)
                .toolName(toolName).toolArgsJson("{\"disp\":\"PTP\"}")
                .humanSummary("summary").expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    @Test
    void confirm_noPendingAction_throwsBusinessException() {
        when(confirmationService.peek(SESSION_ID)).thenReturn(null);

        ConfirmVisitActionRequest req = ConfirmVisitActionRequest.builder().actionId("a").confirmed(true).build();

        assertThatThrownBy(() -> service.confirm(SESSION_ID, req, null, null, principal))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(toolExecutor);
    }

    @Test
    void confirm_wrongPendingTool_throwsBusinessException() {
        when(confirmationService.peek(SESSION_ID)).thenReturn(pendingAction("create_ptp"));

        ConfirmVisitActionRequest req = ConfirmVisitActionRequest.builder().actionId("a").confirmed(true).build();

        assertThatThrownBy(() -> service.confirm(SESSION_ID, req, null, null, principal))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void confirm_notOwner_throwsResourceNotFoundException() {
        User otherUser = User.builder().id(UUID.randomUUID()).organizationId(UUID.randomUUID()).build();
        UserPrincipal other = new UserPrincipal(otherUser);

        ConfirmVisitActionRequest req = ConfirmVisitActionRequest.builder().actionId("a").confirmed(true).build();

        assertThatThrownBy(() -> service.confirm(SESSION_ID, req, null, null, other))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void confirm_cancelled_resolvesAndDoesNotCloseSession() {
        when(confirmationService.peek(SESSION_ID)).thenReturn(pendingAction("submit_visit_interview"));
        when(confirmationService.resolve(SESSION_ID, false, principal)).thenReturn("Action cancelled: summary");

        ConfirmVisitActionRequest req = ConfirmVisitActionRequest.builder().actionId("a").confirmed(false).build();

        ChatResponse response = service.confirm(SESSION_ID, req, null, null, principal);

        assertThat(response.getReply()).contains("cancelled");
        verify(sessionRepository, never()).closeSession(any(), any());
        verifyNoInteractions(toolExecutor);
    }

    @Test
    void confirm_missingGps_throwsBusinessException() {
        when(confirmationService.peek(SESSION_ID)).thenReturn(pendingAction("submit_visit_interview"));

        ConfirmVisitActionRequest req = ConfirmVisitActionRequest.builder().actionId("a").confirmed(true).build();
        MockMultipartFile image1 = new MockMultipartFile("image1", "site.jpg", "image/jpeg", new byte[]{1});

        assertThatThrownBy(() -> service.confirm(SESSION_ID, req, image1, null, principal))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("GPS");
    }

    @Test
    void confirm_success_executesClosesSessionAndAudits() throws Exception {
        when(confirmationService.peek(SESSION_ID)).thenReturn(pendingAction("submit_visit_interview"));
        when(storagePort.store(anyString(), any(), any(), any(), anyLong())).thenReturn("stored/key1");
        when(toolExecutor.executeWithOverride(any(), any(), eq(principal)))
                .thenReturn("{\"id\":\"visit-123\"}");

        ConfirmVisitActionRequest req = ConfirmVisitActionRequest.builder()
                .actionId("a").confirmed(true).latitude(12.9).longitude(77.5).build();
        MockMultipartFile image1 = new MockMultipartFile("image1", "site.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ChatResponse response = service.confirm(SESSION_ID, req, image1, null, principal);

        assertThat(response.getReply()).contains("Done.");
        verify(sessionRepository).closeSession(eq(SESSION_ID), any());
        verify(confirmationService).discard(SESSION_ID);
        verify(stepRepository).save(argThat(step ->
                step.getSessionId().equals(SESSION_ID) && step.isWriteTool() && step.isWasConfirmed()));
        verify(storagePort).delete("stored/key1");
    }

    @Test
    void confirm_toolExecutionFails_doesNotCloseSessionOrDiscardPendingAction() throws Exception {
        when(confirmationService.peek(SESSION_ID)).thenReturn(pendingAction("submit_visit_interview"));
        when(storagePort.store(anyString(), any(), any(), any(), anyLong())).thenReturn("stored/key1");
        when(toolExecutor.executeWithOverride(any(), any(), eq(principal)))
                .thenThrow(new BusinessException("Amount collected is required for a PAID disposition."));

        ConfirmVisitActionRequest req = ConfirmVisitActionRequest.builder()
                .actionId("a").confirmed(true).latitude(12.9).longitude(77.5).build();
        MockMultipartFile image1 = new MockMultipartFile("image1", "site.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ChatResponse response = service.confirm(SESSION_ID, req, image1, null, principal);

        assertThat(response.getReply()).contains("Couldn't submit the visit");
        verify(sessionRepository, never()).closeSession(any(), any());
        verify(confirmationService, never()).discard(any());
    }
}
