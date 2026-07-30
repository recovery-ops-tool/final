package com.recoverpro.server.service.impl;

import com.recoverpro.server.client.LlamaMessage;
import com.recoverpro.server.dto.request.ChatRequest;
import com.recoverpro.server.dto.request.ConfirmActionRequest;
import com.recoverpro.server.dto.request.StartSessionRequest;
import com.recoverpro.server.dto.response.AgentContextDto;
import com.recoverpro.server.dto.response.ChatMessageResponse;
import com.recoverpro.server.dto.response.ChatResponse;
import com.recoverpro.server.dto.response.SessionResponse;
import com.recoverpro.server.entity.ChatMessage;
import com.recoverpro.server.entity.ChatSession;
import com.recoverpro.server.enums.ChatRole;
import com.recoverpro.server.enums.SafetyDecision;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.exception.SessionInactiveException;
import com.recoverpro.server.service.ai.ChatRateLimiter;
import com.recoverpro.server.service.safety.DataSanitizer;
import com.recoverpro.server.service.safety.InputSafetyFilter;
import com.recoverpro.server.service.safety.OutputSafetyFilter;
import com.recoverpro.server.service.safety.SafetyFilterResult;
import com.recoverpro.server.lucien.agent.AgentLoopResult;
import com.recoverpro.server.lucien.agent.ConfirmationService;
import com.recoverpro.server.lucien.agent.LucienAgentLoop;
import com.recoverpro.server.lucien.tool.ToolRegistry;
import com.recoverpro.server.prompt.DefaultSystemPrompt;
import com.recoverpro.server.prompt.SystemPromptBuilder;
import com.recoverpro.server.repository.ChatMessageRepository;
import com.recoverpro.server.repository.ChatSessionRepository;
import com.recoverpro.server.security.OrgIsolationGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AgentContextService;
import com.recoverpro.server.service.AllocationService;
import com.recoverpro.server.service.LucienService;
import com.recoverpro.server.service.LucienTokenBudgetService;
import com.recoverpro.server.service.SystemPromptService;
import com.recoverpro.server.service.VisitInterviewContextService;
import com.recoverpro.server.service.ai.ContextAssembler;
import com.recoverpro.server.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LucienServiceImpl implements LucienService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final InputSafetyFilter inputSafetyFilter;
    private final OutputSafetyFilter outputSafetyFilter;
    private final SystemPromptBuilder systemPromptBuilder;
    private final SystemPromptService systemPromptService;
    private final AgentContextService agentContextService;
    private final ContextAssembler contextAssembler;
    private final DataSanitizer dataSanitizer;
    private final ChatRateLimiter chatRateLimiter;
    private final LucienTokenBudgetService tokenBudgetService;
    private final LucienAgentLoop agentLoop;
    private final ToolRegistry toolRegistry;
    private final ConfirmationService confirmationService;
    private final OrgIsolationGuard orgIsolationGuard;
    private final AllocationService allocationService;
    private final VisitInterviewContextService visitInterviewContextService;

    @Value("${lucien.context.max-history-messages:20}")
    private int maxHistoryMessages;

    @Value("${lucien.llama.model:llama3}")
    private String modelName;

    @Override
    @Transactional
    public SessionResponse startSession(StartSessionRequest request, UserPrincipal principal) {
        // Derive the acting agent from the authenticated caller, never the request body -
        // otherwise any agent could start (and force-close) a session as someone else (SEC-PLAN S5).
        UUID agentId = principal.getId();
        log.info("Starting Lucien session for agentId={}", agentId);

        UUID allocationId = request.getAllocationId();
        if (allocationId != null) {
            // getAllocationById already enforces org isolation (throws ResourceNotFoundException
            // for a different org) via OrgIsolationGuard reading the security context.
            allocationService.getAllocationById(allocationId);
            log.info("Starting Lucien visit-interview session: agentId={}, allocationId={}", agentId, allocationId);
        }

        sessionRepository.closeAllSessionsForAgent(agentId, Instant.now());
        String safeFirstName = dataSanitizer.sanitizeAgentName(request.getAgentFirstName());
        ChatSession session = ChatSession.builder()
                .agentId(agentId)
                .agentFirstName(safeFirstName)
                .allocationId(allocationId)
                .isActive(true)
                .totalMessages(0)
                .build();
        ChatSession saved = sessionRepository.save(session);
        log.info("Lucien session created: id={}, agentId={}, allocationId={}",
                saved.getId(), saved.getAgentId(), saved.getAllocationId());
        return toSessionResponse(saved);
    }

    @Override
    @Transactional
    public ChatResponse chat(ChatRequest request, UserPrincipal principal) {
        long start = System.currentTimeMillis();

        ChatSession session = sessionRepository.findByIdAndIsActiveTrue(request.getSessionId())
                .orElseThrow(() -> new SessionInactiveException(
                        "Session not found or no longer active: " + request.getSessionId()));
        assertSessionAccess(session, principal);

        chatRateLimiter.checkAndRecord(session.getAgentId());

        UUID orgId = tokenBudgetService.resolveOrgId(session.getAgentId()).orElse(null);
        tokenBudgetService.checkBudget(orgId);

        log.info("Lucien chat -- sessionId={}, agentId={}", session.getId(), session.getAgentId());

        SafetyFilterResult inputResult = inputSafetyFilter.filter(request.getMessage());

        if (!inputResult.isAllowed()) {
            log.warn("Input blocked sessionId={}: decision={}", session.getId(), inputResult.getDecision());
            ChatMessage blocked = persistMessage(session, ChatRole.USER,
                    inputResult.getReason(), null,
                    inputResult.getDecision(), null, null, null, true, inputResult.getReason());
            sessionRepository.incrementMessageCount(session.getId());
            return ChatResponse.builder()
                    .messageId(blocked.getId())
                    .sessionId(session.getId())
                    .reply(inputResult.getReason())
                    .blocked(true)
                    .blockReason(inputResult.getReason())
                    .inputSafetyDecision(inputResult.getDecision())
                    .latencyMs(System.currentTimeMillis() - start)
                    .timestamp(Instant.now())
                    .modelName(modelName)
                    .build();
        }

        List<LlamaMessage> messages = buildLlamaMessages(session, principal);

        AgentLoopResult loopResult = agentLoop.run(
                messages, inputResult.getSanitizedContent(), session.getId(), principal);

        long latencyMs = System.currentTimeMillis() - start;

        return switch (loopResult) {
            case AgentLoopResult.ConfirmationRequired cr -> {
                persistMessage(session, ChatRole.USER,
                        inputResult.getSanitizedContent(), null,
                        SafetyDecision.ALLOWED, null, null, null, false, null);
                String confirmPrompt = "Please confirm: " + cr.actionSummary();
                ChatMessage assistantMsg = persistMessage(session, ChatRole.ASSISTANT,
                        confirmPrompt, null, null, SafetyDecision.ALLOWED,
                        cr.inputTokens(), cr.outputTokens(), false, null);
                sessionRepository.incrementBy(session.getId(), 2);
                tokenBudgetService.recordUsage(orgId, cr.inputTokens(), cr.outputTokens());
                yield ChatResponse.builder()
                        .messageId(assistantMsg.getId())
                        .sessionId(session.getId())
                        .reply(confirmPrompt)
                        .blocked(false)
                        .confirmationRequired(true)
                        .pendingActionId(cr.pendingActionId())
                        .pendingActionSummary(cr.actionSummary())
                        .pendingToolName(cr.toolName())
                        .latencyMs(latencyMs)
                        .timestamp(Instant.now())
                        .modelName(modelName)
                        .build();
            }
            case AgentLoopResult.FinalAnswer fa -> {
                SafetyFilterResult outputResult = outputSafetyFilter.filter(fa.text());
                if (!outputResult.isAllowed()) {
                    log.warn("Output blocked for sessionId={}", session.getId());
                    String fallback = "I'm sorry, I wasn't able to generate an appropriate response. Please try rephrasing.";
                    persistMessage(session, ChatRole.USER, inputResult.getSanitizedContent(), null,
                            SafetyDecision.ALLOWED, null, null, null, false, null);
                    ChatMessage assistantMsg = persistMessage(session, ChatRole.ASSISTANT,
                            fallback, null, null, outputResult.getDecision(),
                            fa.inputTokens(), fa.outputTokens(), true, outputResult.getReason());
                    sessionRepository.incrementBy(session.getId(), 2);
                    yield ChatResponse.builder()
                            .messageId(assistantMsg.getId())
                            .sessionId(session.getId())
                            .reply(fallback)
                            .blocked(true)
                            .blockReason(outputResult.getReason())
                            .outputSafetyDecision(outputResult.getDecision())
                            .latencyMs(latencyMs)
                            .timestamp(Instant.now())
                            .modelName(modelName)
                            .build();
                }
                persistMessage(session, ChatRole.USER, inputResult.getSanitizedContent(), null,
                        SafetyDecision.ALLOWED, null, null, null, false, null);
                String finalReply = dataSanitizer.stripPii(outputResult.getSanitizedContent());
                ChatMessage assistantMsg = persistMessage(session, ChatRole.ASSISTANT,
                        finalReply, null, null, SafetyDecision.ALLOWED,
                        fa.inputTokens(), fa.outputTokens(), false, null);
                assistantMsg.setLatencyMs(latencyMs);
                assistantMsg.setModelName(modelName);
                messageRepository.save(assistantMsg);
                sessionRepository.incrementBy(session.getId(), 2);
                tokenBudgetService.recordUsage(orgId, fa.inputTokens(), fa.outputTokens());
                log.info("Lucien chat complete sessionId={}, latency={}ms, tokens={}+{}",
                        session.getId(), latencyMs, fa.inputTokens(), fa.outputTokens());
                yield ChatResponse.builder()
                        .messageId(assistantMsg.getId())
                        .sessionId(session.getId())
                        .reply(finalReply)
                        .blocked(false)
                        .inputSafetyDecision(SafetyDecision.ALLOWED)
                        .outputSafetyDecision(SafetyDecision.ALLOWED)
                        .latencyMs(latencyMs)
                        .timestamp(Instant.now())
                        .modelName(modelName)
                        .build();
            }
        };
    }

    @Override
    @Transactional
    public ChatResponse confirmAction(String sessionId, ConfirmActionRequest request, UserPrincipal principal) {
        long start = System.currentTimeMillis();

        ChatSession session = sessionRepository.findByIdAndIsActiveTrue(sessionId)
                .orElseThrow(() -> new SessionInactiveException("Session not found or inactive: " + sessionId));
        assertSessionAccess(session, principal);

        String result = confirmationService.resolve(sessionId, request.isConfirmed(), principal);

        String reply = request.isConfirmed()
                ? "Done. " + dataSanitizer.stripPii(result)
                : result;

        ChatMessage assistantMsg = persistMessage(session, ChatRole.ASSISTANT,
                reply, null, null, SafetyDecision.ALLOWED, null, null, false, null);
        sessionRepository.incrementMessageCount(session.getId());

        return ChatResponse.builder()
                .messageId(assistantMsg.getId())
                .sessionId(sessionId)
                .reply(reply)
                .blocked(false)
                .latencyMs(System.currentTimeMillis() - start)
                .timestamp(Instant.now())
                .modelName(modelName)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public SessionResponse getSession(String sessionId, UserPrincipal principal) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found: " + sessionId));
        assertSessionAccess(session, principal);
        return toSessionResponse(session);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SessionResponse> getSessionsByAgent(UUID agentId, Pageable pageable, UserPrincipal principal) {
        assertAgentAccess(agentId, principal);
        return sessionRepository.findAllByAgentIdOrderByCreatedAtDesc(agentId, pageable)
                .map(this::toSessionResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getSessionHistory(String sessionId, UserPrincipal principal) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found: " + sessionId));
        assertSessionAccess(session, principal);
        return messageRepository.findAllBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream().map(this::toMessageResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void closeSession(String sessionId, UserPrincipal principal) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Active session not found: " + sessionId));
        assertSessionAccess(session, principal);
        log.info("Closing Lucien session: {}", sessionId);
        int updated = sessionRepository.closeSession(sessionId, Instant.now());
        if (updated == 0) {
            throw new ResourceNotFoundException("Active session not found: " + sessionId);
        }
    }

    @Override
    @Transactional
    public void deleteSession(String sessionId, UserPrincipal principal) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found: " + sessionId));
        assertSessionAccess(session, principal);
        log.info("DPDP erasure -- deleting Lucien session: {}", sessionId);
        int messages = messageRepository.deleteBySessionId(sessionId);
        int sessions = sessionRepository.deleteSessionById(sessionId);
        if (sessions == 0) {
            throw new ResourceNotFoundException("Session not found: " + sessionId);
        }
        log.info("Erased session {}: {} messages, {} session records", sessionId, messages, sessions);
    }

    // ─── private helpers ──────────────────────────────────────────────────────

    /**
     * The owning agent can always access their own session. Otherwise, the caller must be a
     * same-org supervisor (SEC-PLAN S5) - ChatSession has no organizationId column, so org is
     * resolved by joining through the session's agentId to that agent's own User.organizationId.
     */
    private void assertSessionAccess(ChatSession session, UserPrincipal principal) {
        if (principal.getId().equals(session.getAgentId())) {
            return;
        }
        if (!isSupervisor(principal) || !belongsToAgentsOrg(session.getAgentId())) {
            throw new ResourceNotFoundException("Session not found: " + session.getId());
        }
    }

    private void assertAgentAccess(UUID agentId, UserPrincipal principal) {
        if (principal.getId().equals(agentId)) {
            return;
        }
        if (!isSupervisor(principal) || !belongsToAgentsOrg(agentId)) {
            throw new ResourceNotFoundException("Agent not found: " + agentId);
        }
    }

    private boolean isSupervisor(UserPrincipal principal) {
        return principal.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_PLATFORM_ADMIN") || a.equals("ROLE_ORG_ADMIN")
                        || a.equals("ROLE_MANAGER") || a.equals("ROLE_TL"));
    }

    private boolean belongsToAgentsOrg(UUID agentId) {
        UUID agentOrgId = tokenBudgetService.resolveOrgId(agentId).orElse(null);
        return orgIsolationGuard.belongsToOrg(agentOrgId);
    }

    private List<LlamaMessage> buildLlamaMessages(ChatSession session, UserPrincipal principal) {
        AgentContextDto ctx = agentContextService.buildContext(
                session.getId(), session.getAgentId(), session.getAgentFirstName());
        boolean isVisitInterview = session.getAllocationId() != null;
        String promptTemplate = systemPromptService.resolveActiveTemplate(
                isVisitInterview ? DefaultSystemPrompt.INTERVIEW_KEY : DefaultSystemPrompt.KEY);
        String toolSchemas = toolRegistry.buildSchemaBlock();
        String contextBlock = isVisitInterview
                ? visitInterviewContextService.buildContextBlock(session.getAllocationId())
                : contextAssembler.assembleFor(session.getAgentId(), principal.getOrganizationId());
        String systemPrompt = systemPromptBuilder.buildWithTools(promptTemplate, ctx, toolSchemas + contextBlock);

        List<LlamaMessage> messages = new ArrayList<>();
        messages.add(LlamaMessage.builder().role("system").content(systemPrompt).build());

        List<ChatMessage> history = messageRepository.findRecentBySessionId(session.getId(), maxHistoryMessages);
        history.stream()
                .filter(m -> !m.getWasBlocked())
                .forEach(m -> messages.add(LlamaMessage.builder()
                        .role(m.getRole() == ChatRole.USER ? "user" : "assistant")
                        .content(m.getContent())
                        .build()));

        return messages;
    }

    private ChatMessage persistMessage(ChatSession session, ChatRole role,
                                       String content, String rawInput,
                                       SafetyDecision inputDecision, SafetyDecision outputDecision,
                                       Integer inputTokens, Integer outputTokens,
                                       boolean wasBlocked, String blockReason) {
        ChatMessage msg = ChatMessage.builder()
                .session(session)
                .agentId(session.getAgentId())
                .role(role)
                .content(content)
                .inputSafetyDecision(inputDecision)
                .outputSafetyDecision(outputDecision)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .wasBlocked(wasBlocked)
                .blockReason(blockReason)
                .modelName(modelName)
                .build();
        return messageRepository.save(msg);
    }

    private SessionResponse toSessionResponse(ChatSession session) {
        return SessionResponse.builder()
                .sessionId(session.getId())
                .agentId(session.getAgentId())
                .agentFirstName(session.getAgentFirstName())
                .active(session.getIsActive())
                .totalMessages(session.getTotalMessages())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    private ChatMessageResponse toMessageResponse(ChatMessage msg) {
        return ChatMessageResponse.builder()
                .id(msg.getId())
                .role(msg.getRole())
                .content(msg.getContent())
                .wasBlocked(msg.getWasBlocked())
                .createdAt(msg.getCreatedAt())
                .build();
    }
}
