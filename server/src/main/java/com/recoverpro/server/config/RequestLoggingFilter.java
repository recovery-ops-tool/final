package com.recoverpro.server.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Component
@Order(1)
public class RequestLoggingFilter implements Filter {

    // SYSTEM 13 TASK 13.1: was "reqId" -- src/main/resources/logback-spring.xml's prod JSON
    // encoder has always requested MDC key "requestId" (includeMdcKeyName), so every prod JSON
    // log line has been missing its request-correlation field since that encoder was written.
    // Renaming the constant's value is safe: every other reference in the codebase (AuditServiceImpl)
    // reads MDC.get(RequestLoggingFilter.MDC_KEY) symbolically, not the literal string.
    public static final String MDC_KEY = "requestId";
    public static final String HEADER  = "X-Request-Id";

    private static final int MAX_INBOUND_ID_LEN = 64;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]+");

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain chain) throws IOException, ServletException {
        var request  = (HttpServletRequest)  servletRequest;
        var response = (HttpServletResponse) servletResponse;

        String requestId = sanitiseInbound(request.getHeader(HEADER));
        if (requestId == null) requestId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);

        long start = System.currentTimeMillis();
        try {
            String agentId = request.getHeader("X-Agent-Id");
            log.info("--> {} {} agentId={} from={}",
                    request.getMethod(), request.getRequestURI(),
                    agentId != null ? agentId : "N/A", request.getRemoteAddr());

            chain.doFilter(request, response);

            log.info("<-- {} {} status={} {}ms",
                    request.getMethod(), request.getRequestURI(),
                    response.getStatus(), System.currentTimeMillis() - start);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String sanitiseInbound(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_INBOUND_ID_LEN) return null;
        return SAFE_ID.matcher(raw).matches() ? raw : null;
    }
}
