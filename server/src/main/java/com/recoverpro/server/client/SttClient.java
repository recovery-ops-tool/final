package com.recoverpro.server.client;

import com.recoverpro.server.exception.SttUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Set;

/**
 * Client for the local tts-service's /stt endpoint (FastAPI, wraps faster-whisper) —
 * same always-on-local-process pattern as {@link TtsClient} and {@link LlamaClient}.
 * Shares tts-service's host/port and RestTemplate since it's the same process.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SttClient {

    public static final Set<String> SUPPORTED_LANGS = Set.of("en", "hi", "ta", "kn", "te");

    private final RestTemplate ttsRestTemplate;

    @Value("${lucien.tts.base-url}")
    private String baseUrl;

    @CircuitBreaker(name = "stt", fallbackMethod = "transcribeFallback")
    public String transcribe(byte[] audio, String filename, String contentType, String lang) {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(
                contentType != null && !contentType.isBlank() ? contentType : "audio/webm"));
        ByteArrayResource audioResource = new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return filename != null && !filename.isBlank() ? filename : "dictation.webm";
            }
        };
        HttpEntity<ByteArrayResource> audioPart = new HttpEntity<>(audioResource, fileHeaders);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("audio", audioPart);
        body.add("lang", lang);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<SttResponse> response = ttsRestTemplate.postForEntity(
                    baseUrl + "/stt", entity, SttResponse.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody().text();
            }
            throw new SttUnavailableException("STT service returned non-2xx status: " + response.getStatusCode());

        } catch (ResourceAccessException ex) {
            log.error("STT service is unreachable at {}: {}", baseUrl, ex.getMessage());
            throw new SttUnavailableException("Voice input is temporarily unavailable.");
        } catch (SttUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error communicating with STT service: {}", ex.getMessage(), ex);
            throw new SttUnavailableException("Unexpected error transcribing voice input.");
        }
    }

    public String transcribeFallback(byte[] audio, String filename, String contentType, String lang, Exception ex) {
        log.warn("STT circuit breaker open — returning fast failure. Cause: {}", ex.getMessage());
        throw new SttUnavailableException("Voice input is temporarily unavailable (circuit open).");
    }

    private record SttResponse(String text, String language) {}
}
