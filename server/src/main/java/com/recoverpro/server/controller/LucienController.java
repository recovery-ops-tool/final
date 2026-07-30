package com.recoverpro.server.controller;

import com.recoverpro.server.annotation.RequiresFeature;
import com.recoverpro.server.client.SttClient;
import com.recoverpro.server.client.TtsClient;
import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.config.PlanFeatureMatrix;
import com.recoverpro.server.dto.request.ChatRequest;
import com.recoverpro.server.dto.request.ConfirmActionRequest;
import com.recoverpro.server.dto.request.ConfirmVisitActionRequest;
import com.recoverpro.server.dto.request.SpeakRequest;
import com.recoverpro.server.dto.request.StartSessionRequest;
import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.dto.response.ChatMessageResponse;
import com.recoverpro.server.dto.response.ChatResponse;
import com.recoverpro.server.dto.response.SessionResponse;
import com.recoverpro.server.dto.response.TranscriptionResponse;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.LucienService;
import com.recoverpro.server.service.VisitInterviewService;
import com.recoverpro.server.service.ai.TranslationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/lucien")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class LucienController {

    private final LucienService lucienService;
    private final VisitInterviewService visitInterviewService;
    private final TtsClient ttsClient;
    private final SttClient sttClient;
    private final TranslationService translationService;

    @PostMapping("/sessions")
    @RequiresFeature(PlanFeatureMatrix.LUCIEN_AI)
    public ResponseEntity<ApiResponse<SessionResponse>> startSession(
            @Valid @RequestBody StartSessionRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.info("POST /api/v1/lucien/sessions -- agentId={}", principal.getId());
        SessionResponse session = lucienService.startSession(request, principal);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(session, "Lucien session started."));
    }

    @PostMapping("/chat")
    @RequiresFeature(PlanFeatureMatrix.LUCIEN_AI)
    public ResponseEntity<ApiResponse<ChatResponse>> chat(
            @Valid @RequestBody ChatRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.info("POST /api/v1/lucien/chat -- sessionId={}", request.getSessionId());
        ChatResponse response = lucienService.chat(request, principal);
        return ResponseEntity.ok(ApiResponse.success(response, "Response generated."));
    }

    /**
     * Confirm (confirmed=true) or cancel (confirmed=false) a pending WRITE tool action.
     * Design-doc §6.3 — POST /sessions/{id}/confirm.
     */
    @PostMapping("/sessions/{sessionId}/confirm")
    @RequiresFeature(PlanFeatureMatrix.LUCIEN_AI)
    public ResponseEntity<ApiResponse<ChatResponse>> confirmAction(
            @PathVariable String sessionId,
            @Valid @RequestBody ConfirmActionRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.info("POST /api/v1/lucien/sessions/{}/confirm -- actionId={}, confirmed={}",
                sessionId, request.getActionId(), request.isConfirmed());
        ChatResponse response = lucienService.confirmAction(sessionId, request, principal);
        return ResponseEntity.ok(ApiResponse.success(response,
                request.isConfirmed() ? "Action executed." : "Action cancelled."));
    }

    /**
     * Confirms (or cancels) a pending submit_visit_interview WRITE action for a visit-interview
     * session. Multipart, not plain JSON like /confirm — GPS and the site/selfie photos are
     * captured by the browser and attached here at the moment the FO taps Confirm, never routed
     * through the model. See VisitInterviewService for why this needs its own endpoint.
     */
    @PostMapping(value = "/sessions/{sessionId}/confirm-visit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresFeature(PlanFeatureMatrix.LUCIEN_AI)
    public ResponseEntity<ApiResponse<ChatResponse>> confirmVisitAction(
            @PathVariable String sessionId,
            @Valid @RequestPart("data") ConfirmVisitActionRequest request,
            @RequestPart(value = "image1", required = false) MultipartFile image1,
            @RequestPart(value = "image2", required = false) MultipartFile image2,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.info("POST /api/v1/lucien/sessions/{}/confirm-visit -- confirmed={}", sessionId, request.isConfirmed());
        ChatResponse response = visitInterviewService.confirm(sessionId, request, image1, image2, principal);
        return ResponseEntity.ok(ApiResponse.success(response,
                request.isConfirmed() ? "Visit submitted." : "Action cancelled."));
    }

    @PostMapping("/sessions/{sessionId}/close")
    @RequiresFeature(PlanFeatureMatrix.LUCIEN_AI)
    public ResponseEntity<ApiResponse<Void>> closeSession(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.info("POST /api/v1/lucien/sessions/{}/close", sessionId);
        lucienService.closeSession(sessionId, principal);
        return ResponseEntity.ok(ApiResponse.success(null, "Session closed."));
    }

    @GetMapping("/sessions/{sessionId}")
    @RequiresFeature(PlanFeatureMatrix.LUCIEN_AI)
    public ResponseEntity<ApiResponse<SessionResponse>> getSession(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.debug("GET /api/v1/lucien/sessions/{}", sessionId);
        return ResponseEntity.ok(ApiResponse.success(
                lucienService.getSession(sessionId, principal), "Session fetched."));
    }

    @GetMapping("/sessions/{sessionId}/history")
    @RequiresFeature(PlanFeatureMatrix.LUCIEN_AI)
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> getSessionHistory(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.debug("GET /api/v1/lucien/sessions/{}/history", sessionId);
        return ResponseEntity.ok(ApiResponse.success(
                lucienService.getSessionHistory(sessionId, principal), "History fetched."));
    }

    @GetMapping("/agents/{agentId}/sessions")
    @RequiresFeature(PlanFeatureMatrix.LUCIEN_AI)
    public ResponseEntity<ApiResponse<PagedResponse<SessionResponse>>> getAgentSessions(
            @PathVariable UUID agentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.debug("GET /api/v1/lucien/agents/{}/sessions", agentId);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<SessionResponse> sessions = lucienService.getSessionsByAgent(agentId, pageable, principal);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(sessions), "Agent sessions fetched."));
    }

    /**
     * Synthesizes speech for Lucien replies in Hindi/Tamil/Kannada/Telugu via the
     * local Indic TTS microservice (tts-service/, wraps ai4bharat/IndicF5). English
     * playback stays on the browser's built-in speechSynthesis — no backend call.
     */
    @PostMapping(value = "/speak", produces = "audio/wav")
    @RequiresFeature(PlanFeatureMatrix.LUCIEN_AI)
    public ResponseEntity<byte[]> speak(@Valid @RequestBody SpeakRequest request) {
        log.debug("POST /api/v1/lucien/speak -- lang={}, textLen={}", request.getLang(), request.getText().length());
        String textToSpeak = translationService.translateForSpeech(request.getText(), request.getLang());
        byte[] audio = ttsClient.synthesize(textToSpeak, request.getLang());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(audio);
    }

    /**
     * Transcribes a recorded voice clip via the local Whisper microservice
     * (tts-service/, faster-whisper). Powers the composer's mic button — the
     * browser's own SpeechRecognition API was dropped for dictation because
     * Chrome routes it through an unsupported third-party Google endpoint
     * that fails silently for non-google.com origins.
     */
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresFeature(PlanFeatureMatrix.LUCIEN_AI)
    public ResponseEntity<ApiResponse<TranscriptionResponse>> transcribe(
            @RequestPart("audio") MultipartFile audio,
            @RequestParam(defaultValue = "en") String lang,
            @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        log.debug("POST /api/v1/lucien/transcribe -- agentId={}, lang={}, size={}",
                principal.getId(), lang, audio.getSize());
        if (audio.isEmpty()) {
            throw new BusinessException("Audio clip is required.");
        }
        if (!SttClient.SUPPORTED_LANGS.contains(lang.toLowerCase())) {
            throw new BusinessException("Unsupported language '" + lang + "'. Supported: " + SttClient.SUPPORTED_LANGS);
        }
        String text = sttClient.transcribe(audio.getBytes(), audio.getOriginalFilename(), audio.getContentType(), lang);
        return ResponseEntity.ok(ApiResponse.success(new TranscriptionResponse(text), "Transcribed."));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<ApiResponse<Void>> deleteSession(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.info("DELETE /api/v1/lucien/sessions/{} -- DPDP erasure", sessionId);
        lucienService.deleteSession(sessionId, principal);
        return ResponseEntity.ok(ApiResponse.success(null, "Session permanently deleted."));
    }
}
