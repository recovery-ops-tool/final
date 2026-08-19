package com.recoverpro.server.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.recoverpro.server.util.ClientIpResolver;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenRateLimitFilter extends OncePerRequestFilter {

    private static final String REFRESH_PATH   = "/api/v1/auth/refresh";
    private static final String KEY_PREFIX     = "rate:refresh:";
    private static final int    MAX_PER_WINDOW = 10;
    private static final long   WINDOW_SECONDS = 60L;

    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && REFRESH_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ip  = ClientIpResolver.resolve(request);
        String key = KEY_PREFIX + ip;

        try {
            Long count = redisTemplate.execute(INCREMENT_SCRIPT, List.of(key), String.valueOf(WINDOW_SECONDS));
            if (count != null && count > MAX_PER_WINDOW) {
                log.warn("Refresh rate limit exceeded: ip={} count={}", ip, count);
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"error\":\"Too many refresh attempts. Try again in 1 minute.\",\"status\":429}");
                return;
            }
        } catch (Exception e) {
            log.error("Refresh rate limiter Redis error — failing closed: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Refresh temporarily unavailable. Retry shortly.\",\"status\":503}");
            return;
        }

        chain.doFilter(request, response);
    }
}
