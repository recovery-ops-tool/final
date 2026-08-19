package com.recoverpro.server.service.ai;

import com.recoverpro.server.common.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRateLimiter {

    private final StringRedisTemplate redisTemplate;

    @Value("${lucien.rate-limit.max-requests:20}")
    private int maxRequests;

    @Value("${lucien.rate-limit.window-seconds:60}")
    private long windowSeconds;

    // Ambient mode fires once per VAD-segmented utterance during a live doorstep conversation --
    // much higher frequency than typed chat -- so it needs its own, more generous limit rather
    // than reusing the chat one (which would falsely rate-limit a normal ambient visit).
    @Value("${lucien.rate-limit.ambient.max-requests:120}")
    private int ambientMaxRequests;

    @Value("${lucien.rate-limit.ambient.window-seconds:60}")
    private long ambientWindowSeconds;

    // SYSTEM 07 TASK 7.3: /speak (TTS synthesis) and /transcribe (STT) both call an external
    // voice microservice per request -- real cost/compute per call, same "unthrottled AI
    // endpoint is a billing incident waiting to happen" reasoning as chat, and neither had any
    // limiter before this. One synthesis call per non-English Lucien reply and one transcription
    // per mic-button press are the expected normal rates -- generous enough not to interfere,
    // still bounded.
    @Value("${lucien.rate-limit.speak.max-requests:30}")
    private int speakMaxRequests;

    @Value("${lucien.rate-limit.speak.window-seconds:60}")
    private long speakWindowSeconds;

    @Value("${lucien.rate-limit.transcribe.max-requests:30}")
    private int transcribeMaxRequests;

    @Value("${lucien.rate-limit.transcribe.window-seconds:60}")
    private long transcribeWindowSeconds;

    private static final String KEY_PREFIX = "rate:chat:";
    private static final String AMBIENT_KEY_PREFIX = "rate:ambient:";
    private static final String SPEAK_KEY_PREFIX = "rate:speak:";
    private static final String TRANSCRIBE_KEY_PREFIX = "rate:transcribe:";

    // Atomic increment + conditional expire in a single Lua script.
    // Prevents the race where two threads both see count==0 and both set TTL,
    // or where increment and expire run on different connections.
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    public void checkAndRecord(UUID agentId) {
        checkAndRecord(KEY_PREFIX + agentId, maxRequests, windowSeconds);
    }

    public void checkAndRecordAmbient(UUID agentId) {
        checkAndRecord(AMBIENT_KEY_PREFIX + agentId, ambientMaxRequests, ambientWindowSeconds);
    }

    public void checkAndRecordSpeak(UUID agentId) {
        checkAndRecord(SPEAK_KEY_PREFIX + agentId, speakMaxRequests, speakWindowSeconds);
    }

    public void checkAndRecordTranscribe(UUID agentId) {
        checkAndRecord(TRANSCRIBE_KEY_PREFIX + agentId, transcribeMaxRequests, transcribeWindowSeconds);
    }

    private void checkAndRecord(String key, int limit, long windowSeconds) {
        try {
            Long count = redisTemplate.execute(INCREMENT_SCRIPT, List.of(key), String.valueOf(windowSeconds));
            if (count != null && count > limit) {
                Long ttl = redisTemplate.getExpire(key);
                long retryAfter = ttl != null && ttl > 0 ? ttl : windowSeconds;
                log.warn("Rate limit exceeded: key={} count={}", key, count);
                throw new RateLimitExceededException(
                        "Too many requests. Limit is %d messages per %ds. Retry after %ds."
                                .formatted(limit, windowSeconds, retryAfter),
                        retryAfter);
            }
        } catch (RateLimitExceededException e) {
            throw e;
        } catch (Exception e) {
            log.error("ChatRateLimiter Redis error — failing closed: {}", e.getMessage());
            throw new RateLimitExceededException(
                    "Chat service temporarily unavailable. Please try again in 30 seconds.", 30L);
        }
    }
}
