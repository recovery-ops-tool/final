package com.recoverpro.server.service.impl;

import com.recoverpro.server.client.LlamaMessage;
import com.recoverpro.server.dto.request.ChatRequest;
import com.recoverpro.server.dto.response.AgentContextDto;
import com.recoverpro.server.dto.response.ChatResponse;
import com.recoverpro.server.entity.ChatMessage;
import com.recoverpro.server.entity.ChatSession;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.enums.ChatRole;
import com.recoverpro.server.lucien.agent.AgentLoopResult;
import com.recoverpro.server.lucien.agent.ConfirmationService;
import com.recoverpro.server.lucien.agent.LucienAgentLoop;
import com.recoverpro.server.lucien.tool.ToolRegistry;
import com.recoverpro.server.repository.ChatMessageRepository;
import com.recoverpro.server.repository.ChatSessionRepository;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AgentContextService;
import com.recoverpro.server.service.LucienTokenBudgetService;
import com.recoverpro.server.service.SystemPromptService;
import com.recoverpro.server.service.ai.ChatRateLimiter;
import com.recoverpro.server.service.ai.ContextAssembler;
import com.recoverpro.server.service.safety.DataSanitizer;
import com.recoverpro.server.service.safety.InputSafetyFilter;
import com.recoverpro.server.service.safety.OutputSafetyFilter;
import com.recoverpro.server.service.safety.SafetyFilterResult;
import com.recoverpro.server.prompt.SystemPromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-PLAN SP43: one chat turn persists exactly two ChatMessage rows (user echo + assistant
 * reply), so the session's totalMessages counter must go up by exactly 2 -- previously done via
 * two separate incrementMessageCount() calls, which this test locks down as a single atomic
 * incrementBy(id, 2) instead.
 */
@ExtendWith(MockitoExtension.class)
class LucienServiceImplTest {

    @Mock private ChatSessionRepository sessionRepository;
    @Mock private ChatMessageRepository messageRepository;
    @Mock private InputSafetyFilter inputSafetyFilter;
    @Mock private OutputSafetyFilter outputSafetyFilter;
    @Mock private SystemPromptBuilder systemPromptBuilder;
    @Mock private SystemPromptService systemPromptService;
    @Mock private AgentContextService agentContextService;
    @Mock private ContextAssembler contextAssembler;
    @Mock private DataSanitizer dataSanitizer;
    @Mock private ChatRateLimiter chatRateLimiter;
    @Mock private LucienTokenBudgetService tokenBudgetService;
    @Mock private LucienAgentLoop agentLoop;
    @Mock private ToolRegistry toolRegistry;
    @Mock private ConfirmationService confirmationService;
    @Mock private OrgIsolationGuard orgIsolationGuard;
    @Mock private com.recoverpro.server.service.AllocationService allocationService;
    @Mock private com.recoverpro.server.service.VisitInterviewContextService visitInterviewContextService;

    private LucienServiceImpl service;
    private UUID agentId;
    private UserPrincipal principal;
    private ChatSession session;

    @BeforeEach
    void setUp() {
        service = new LucienServiceImpl(sessionRepository, messageRepository, inputSafetyFilter,
                outputSafetyFilter, systemPromptBuilder, systemPromptService, agentContextService,
                contextAssembler, dataSanitizer, chatRateLimiter, tokenBudgetService, agentLoop,
                toolRegistry, confirmationService, orgIsolationGuard, allocationService,
                visitInterviewContextService);

        agentId = UUID.randomUUID();
        User user = User.builder().id(agentId).organizationId(UUID.randomUUID()).build();
        principal = new UserPrincipal(user);

        session = ChatSession.builder().id("session-1").agentId(agentId).agentFirstName("Alex")
                .isActive(true).totalMessages(0).build();

        lenient().when(sessionRepository.findByIdAndIsActiveTrue("session-1")).thenReturn(Optional.of(session));
        lenient().when(tokenBudgetService.resolveOrgId(agentId)).thenReturn(Optional.of(principal.getOrganizationId()));
        lenient().when(inputSafetyFilter.filter(anyString())).thenReturn(SafetyFilterResult.allowed("hello"));
        lenient().when(agentContextService.buildContext(any(), any(), any()))
                .thenReturn(AgentContextDto.builder().agentFirstName("Alex").build());
        lenient().when(systemPromptService.resolveActiveTemplate(any())).thenReturn("template");
        lenient().when(toolRegistry.buildSchemaBlock()).thenReturn("");
        lenient().when(contextAssembler.assembleFor(any(), any())).thenReturn("");
        lenient().when(systemPromptBuilder.buildWithTools(any(), any(), any())).thenReturn("system prompt");
        lenient().when(messageRepository.findRecentBySessionId(any(), anyInt())).thenReturn(List.of());
        lenient().when(messageRepository.save(any())).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
    }

    @Test
    void chat_finalAnswer_incrementsSessionMessageCountByTwoInOneCall() {
        when(agentLoop.run(any(), eq("hello"), eq("session-1"), eq(principal)))
                .thenReturn(new AgentLoopResult.FinalAnswer("Hi there", 10, 5, List.of()));
        when(outputSafetyFilter.filter("Hi there")).thenReturn(SafetyFilterResult.allowed("Hi there"));
        when(dataSanitizer.stripPii("Hi there")).thenReturn("Hi there");

        ChatRequest request = ChatRequest.builder().sessionId("session-1").message("hello").build();

        ChatResponse response = service.chat(request, principal);

        assertThat(response.isBlocked()).isFalse();
        verify(sessionRepository).incrementBy("session-1", 2);
        verify(sessionRepository, never()).incrementMessageCount(any());
    }
}
