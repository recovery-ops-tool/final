package com.recoverpro.server.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.dto.request.ConfirmVisitActionRequest;
import com.recoverpro.server.dto.response.ChatResponse;
import com.recoverpro.server.entity.ChatMessage;
import com.recoverpro.server.entity.ChatSession;
import com.recoverpro.server.entity.LucienAgentStep;
import com.recoverpro.server.enums.ChatRole;
import com.recoverpro.server.enums.SafetyDecision;
import com.recoverpro.server.exception.SessionInactiveException;
import com.recoverpro.server.lucien.agent.ConfirmationService;
import com.recoverpro.server.lucien.agent.PendingAction;
import com.recoverpro.server.lucien.tool.ToolExecutor;
import com.recoverpro.server.repository.AgentStepRepository;
import com.recoverpro.server.repository.ChatMessageRepository;
import com.recoverpro.server.repository.ChatSessionRepository;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.VisitInterviewService;
import com.recoverpro.server.service.safety.DataSanitizer;
import com.recoverpro.server.service.storage.StoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisitInterviewServiceImpl implements VisitInterviewService {

    private static final String SUBMIT_VISIT_TOOL = "submit_visit_interview";

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final AgentStepRepository stepRepository;
    private final ConfirmationService confirmationService;
    private final ToolExecutor toolExecutor;
    private final StoragePort storagePort;
    private final DataSanitizer dataSanitizer;
    private final ObjectMapper objectMapper;

    @Value("${app.storage.visits-path:./uploads/visits}")
    private String storagePath;

    @Value("${lucien.llama.model:llama3}")
    private String modelName;

    @Override
    @Transactional
    public ChatResponse confirm(String sessionId, ConfirmVisitActionRequest request,
                                 MultipartFile image1, MultipartFile image2, UserPrincipal principal) {
        long start = System.currentTimeMillis();

        ChatSession session = sessionRepository.findByIdAndIsActiveTrue(sessionId)
                .orElseThrow(() -> new SessionInactiveException("Session not found or no longer active: " + sessionId));
        if (!principal.getId().equals(session.getAgentId())) {
            throw new ResourceNotFoundException("Session not found: " + sessionId);
        }
        if (session.getAllocationId() == null) {
            throw new BusinessException("This session is not a visit interview.");
        }

        PendingAction pending = confirmationService.peek(sessionId);
        if (pending == null) {
            throw new BusinessException("No pending action for this session — it may have timed out. Please retry.");
        }
        if (!SUBMIT_VISIT_TOOL.equals(pending.getToolName())) {
            throw new BusinessException("Unexpected pending action for this session.");
        }

        if (!request.isConfirmed()) {
            String result = confirmationService.resolve(sessionId, false, principal);
            ChatMessage assistantMsg = persistMessage(session, result);
            sessionRepository.incrementMessageCount(session.getId());
            return chatResponse(session, assistantMsg, result, start);
        }

        if (request.getLatitude() == null || request.getLongitude() == null) {
            throw new BusinessException("GPS location is required to submit this visit.");
        }
        if (image1 == null || image1.isEmpty()) {
            throw new BusinessException("A site photo is required to submit this visit.");
        }

        List<String> stagedKeys = new ArrayList<>();
        try {
            String image1Key = stageImage(sessionId, "image1", image1);
            stagedKeys.add(image1Key);
            String image2Key = (image2 != null && !image2.isEmpty()) ? stageImage(sessionId, "image2", image2) : null;
            if (image2Key != null) stagedKeys.add(image2Key);

            ObjectNode mergedArgs = buildMergedArgs(pending, session, request, image1Key, image2Key);

            String result;
            try {
                result = toolExecutor.executeWithOverride(pending, mergedArgs, principal);
            } catch (BusinessException e) {
                // User-actionable: after-hours guard, mock-location, missing disposition field.
                // Leave the session open (and the pending action in place, matching
                // ConfirmationService.resolve()'s existing failure behaviour) so the FO can
                // keep chatting with Lucien to fix it and re-submit.
                String reply = "Couldn't submit the visit: " + e.getMessage() + " Let's fix that — tell Lucien what's missing.";
                ChatMessage assistantMsg = persistMessage(session, reply);
                sessionRepository.incrementMessageCount(session.getId());
                return chatResponse(session, assistantMsg, reply, start);
            }

            confirmationService.discard(sessionId);
            stepRepository.save(LucienAgentStep.builder()
                    .sessionId(sessionId)
                    .iteration(-1) // confirmed-execution step, outside the ReAct loop's iteration count
                    .toolName(pending.getToolName())
                    .toolInput(mergedArgs.toString())
                    .toolOutput(result)
                    .isWriteTool(true)
                    .wasConfirmed(true)
                    .build());

            String reply = "Done. " + dataSanitizer.stripPii(result);
            ChatMessage assistantMsg = persistMessage(session, reply);
            sessionRepository.incrementMessageCount(session.getId());
            sessionRepository.closeSession(sessionId, Instant.now());

            log.info("Visit interview submitted and session closed: sessionId={}, allocationId={}",
                    sessionId, session.getAllocationId());
            return chatResponse(session, assistantMsg, reply, start);
        } finally {
            for (String key : stagedKeys) {
                storagePort.delete(key);
            }
        }
    }

    private ObjectNode buildMergedArgs(PendingAction pending, ChatSession session,
                                        ConfirmVisitActionRequest request,
                                        String image1Key, String image2Key) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(pending.getToolArgsJson());
        } catch (Exception e) {
            throw new BusinessException("Could not read the pending visit details. Please retry from chat.");
        }
        ObjectNode args = parsed.isObject() ? (ObjectNode) parsed : objectMapper.createObjectNode();

        // Server-verified fields override anything the model may have guessed — the model's
        // schema for submit_visit_interview never even exposes these fields.
        args.put("allocationId", session.getAllocationId().toString());
        args.put("lucienSessionId", session.getId());
        args.put("latitude", request.getLatitude());
        args.put("longitude", request.getLongitude());
        if (request.getGpsAccuracy() != null) args.put("gpsAccuracy", request.getGpsAccuracy());
        args.put("mockLocationDetected", Boolean.TRUE.equals(request.getMockLocationDetected()));
        args.put("image1Key", image1Key);
        if (image2Key != null) args.put("image2Key", image2Key);
        return args;
    }

    private String stageImage(String sessionId, String label, MultipartFile file) {
        try {
            String safeName = label + "_" + System.currentTimeMillis();
            String s3Key = "lucien-visit-tmp/" + sessionId + "/" + safeName;
            Path localPath = Paths.get(storagePath, "lucien-tmp", sessionId, safeName);
            return storagePort.store(s3Key, localPath, file.getInputStream(), file.getContentType(), file.getSize());
        } catch (Exception e) {
            log.error("Failed to stage {} for sessionId={}: {}", label, sessionId, e.getMessage());
            throw new BusinessException("Could not upload " + label + ". Please try again.");
        }
    }

    private ChatMessage persistMessage(ChatSession session, String content) {
        ChatMessage msg = ChatMessage.builder()
                .session(session)
                .agentId(session.getAgentId())
                .role(ChatRole.ASSISTANT)
                .content(content)
                .inputSafetyDecision(null)
                .outputSafetyDecision(SafetyDecision.ALLOWED)
                .wasBlocked(false)
                .modelName(modelName)
                .build();
        return messageRepository.save(msg);
    }

    private ChatResponse chatResponse(ChatSession session, ChatMessage assistantMsg, String reply, long start) {
        return ChatResponse.builder()
                .messageId(assistantMsg.getId())
                .sessionId(session.getId())
                .reply(reply)
                .blocked(false)
                .latencyMs(System.currentTimeMillis() - start)
                .timestamp(Instant.now())
                .modelName(modelName)
                .build();
    }
}
