package com.recoverpro.server.client;

import com.recoverpro.server.exception.LlamaUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * SYSTEM 12 TASK 12.2: Lucien LLM call latency, failure rate, and token volume are instrumented
 * here specifically because token volume is a direct cost driver (Sarvam AI/hosted-API billing,
 * per docs/INFRA-CURRENT.md's still-open AI-backend decision) -- this is the one integration in
 * the task's own metrics list where "someone is paying per call" makes the metric matter beyond
 * ordinary observability.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlamaClient {

    private final RestTemplate llamaRestTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Value("${lucien.llama.base-url}")
    private String baseUrl;

    @Value("${lucien.llama.model}")
    private String model;

    @Value("${lucien.llama.max-tokens:1024}")
    private int maxTokens;

    @Value("${lucien.llama.temperature:0.7}")
    private double temperature;

    @Value("${lucien.llama.top-p:0.9}")
    private double topP;

    @Value("${lucien.llama.embedding-model:nomic-embed-text}")
    private String embeddingModel;

    @CircuitBreaker(name = "llama", fallbackMethod = "chatFallback")
    public LlamaResponse chat(List<LlamaMessage> messages) {
        LlamaRequest request = LlamaRequest.builder()
                .model(model)
                .messages(messages)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .topP(topP)
                .stream(false)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<LlamaRequest> entity = new HttpEntity<>(request, headers);

        log.debug("Sending {} messages to Llama at {}", messages.size(), baseUrl);
        long start = System.currentTimeMillis();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            ResponseEntity<LlamaResponse> response = llamaRestTemplate.postForEntity(
                    baseUrl + "/v1/chat/completions",
                    entity,
                    LlamaResponse.class
            );

            long latency = System.currentTimeMillis() - start;
            log.info("Llama responded in {}ms, choices={}", latency,
                    response.getBody() != null && response.getBody().getChoices() != null
                            ? response.getBody().getChoices().size() : 0);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                stopTimer(sample, "chat", "success");
                recordTokenUsage(response.getBody().getUsage());
                return response.getBody();
            }

            stopTimer(sample, "chat", "failure");
            throw new LlamaUnavailableException("Llama returned non-2xx status: " + response.getStatusCode());

        } catch (ResourceAccessException ex) {
            stopTimer(sample, "chat", "failure");
            log.error("Llama server is unreachable at {}: {}", baseUrl, ex.getMessage());
            throw new LlamaUnavailableException("FRIDAY AI is temporarily unavailable. Please try again shortly.");
        } catch (LlamaUnavailableException ex) {
            stopTimer(sample, "chat", "failure");
            throw ex;
        } catch (Exception ex) {
            stopTimer(sample, "chat", "failure");
            log.error("Unexpected error communicating with Llama: {}", ex.getMessage(), ex);
            throw new LlamaUnavailableException("Unexpected error communicating with AI service.");
        }
    }

    public LlamaResponse chatFallback(List<LlamaMessage> messages, Exception ex) {
        log.warn("Llama circuit breaker open — returning fast failure. Cause: {}", ex.getMessage());
        llmCallCounter("chat", "circuit_open").increment();
        throw new LlamaUnavailableException(
                "FRIDAY AI is temporarily unavailable (circuit open). Please try again shortly.");
    }

    private void stopTimer(Timer.Sample sample, String operation, String outcome) {
        sample.stop(Timer.builder("lucien_llm_call_duration_seconds")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(meterRegistry));
        llmCallCounter(operation, outcome).increment();
    }

    private Counter llmCallCounter(String operation, String outcome) {
        return Counter.builder("lucien_llm_call_events_total")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    /** Direct cost driver for a hosted-API LLM backend -- see class javadoc. */
    private void recordTokenUsage(LlamaResponse.Usage usage) {
        if (usage == null) return;
        meterRegistry.counter("lucien_llm_tokens_total", "token_type", "prompt")
                .increment(usage.getPromptTokens());
        meterRegistry.counter("lucien_llm_tokens_total", "token_type", "completion")
                .increment(usage.getCompletionTokens());
    }

    /**
     * Embeds a batch of texts. Returns one float vector per input, same order.
     * Uses its own circuit breaker (distinct from "llama" on {@link #chat}) so an
     * embedding-model outage (e.g. the model isn't pulled) can't trip the breaker
     * that guards chat, which is otherwise unrelated.
     */
    @CircuitBreaker(name = "llamaEmbedding", fallbackMethod = "embedFallback")
    public List<List<Float>> embed(List<String> texts) {
        LlamaEmbeddingRequest request = LlamaEmbeddingRequest.builder()
                .model(embeddingModel)
                .input(texts)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<LlamaEmbeddingRequest> entity = new HttpEntity<>(request, headers);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            ResponseEntity<LlamaEmbeddingResponse> response = llamaRestTemplate.postForEntity(
                    baseUrl + "/v1/embeddings", entity, LlamaEmbeddingResponse.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null
                    && response.getBody().getData() != null) {
                stopTimer(sample, "embed", "success");
                return response.getBody().getData().stream()
                        .sorted((a, b) -> Integer.compare(a.getIndex(), b.getIndex()))
                        .map(LlamaEmbeddingResponse.EmbeddingData::getEmbedding)
                        .toList();
            }
            stopTimer(sample, "embed", "failure");
            throw new LlamaUnavailableException("Llama embeddings returned non-2xx status: " + response.getStatusCode());

        } catch (ResourceAccessException ex) {
            stopTimer(sample, "embed", "failure");
            log.error("Llama embeddings server unreachable at {}: {}", baseUrl, ex.getMessage());
            throw new LlamaUnavailableException("Embedding service is temporarily unavailable. Please try again shortly.");
        } catch (LlamaUnavailableException ex) {
            stopTimer(sample, "embed", "failure");
            throw ex;
        } catch (Exception ex) {
            stopTimer(sample, "embed", "failure");
            log.error("Unexpected error calling Llama embeddings: {}", ex.getMessage(), ex);
            throw new LlamaUnavailableException("Unexpected error communicating with the embedding service.");
        }
    }

    public List<List<Float>> embedFallback(List<String> texts, Exception ex) {
        log.warn("Llama embeddings circuit breaker open — returning fast failure. Cause: {}", ex.getMessage());
        llmCallCounter("embed", "circuit_open").increment();
        throw new LlamaUnavailableException("Embedding service is temporarily unavailable (circuit open).");
    }

    public String extractReply(LlamaResponse response) {
        if (response == null
                || response.getChoices() == null
                || response.getChoices().isEmpty()
                || response.getChoices().get(0).getMessage() == null) {
            throw new LlamaUnavailableException("Llama returned an empty response.");
        }
        return response.getChoices().get(0).getMessage().getContent();
    }
}