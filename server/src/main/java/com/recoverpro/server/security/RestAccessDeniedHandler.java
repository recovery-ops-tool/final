package com.recoverpro.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.common.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Every {@code @PreAuthorize} failure across the app funnels through here -- the one place that
 * can record ACCESS_DENIED without touching every controller. The audit write itself (throttled,
 * best-effort) is delegated to {@link AccessDenialAuditor}, shared with {@link
 * RestAuthenticationEntryPoint} and {@link com.recoverpro.server.security.jwt.JwtAuthenticationFilter}
 * for the 401 side of the same concern (SYSTEM 09 TASK 9.4) -- ACCESS_DENIED defaults to
 * WARNING, not HIGH/CRITICAL, so an ordinary 403 doesn't read as loud as a real security event.
 */
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final AccessDenialAuditor accessDenialAuditor;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                        AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.FORBIDDEN.value())
                .error("Forbidden")
                .message("Access denied")
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        response.getWriter().write(MAPPER.writeValueAsString(body));

        var auth = SecurityContextHolder.getContext().getAuthentication();
        UUID userId = (auth != null && auth.getPrincipal() instanceof UserPrincipal up)
                ? up.getId() : null;
        accessDenialAuditor.recordAccessDenied(request, userId);
    }
}
