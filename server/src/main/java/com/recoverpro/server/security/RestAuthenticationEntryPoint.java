package com.recoverpro.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverpro.server.common.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * SYSTEM 09 TASK 9.4.b: reached whenever a request has no valid Authentication at all (missing
 * token, or one {@link com.recoverpro.server.security.jwt.JwtAuthenticationFilter} judged invalid
 * and let fall through unauthenticated rather than reject outright) -- previously wrote a 401
 * response but no audit trail whatsoever, leaving 401s completely invisible next to 403s'
 * ACCESS_DENIED rows. Now records AUTH_UNAUTHORIZED via {@link AccessDenialAuditor}, the same
 * throttled/best-effort path {@link RestAccessDeniedHandler} uses for the 403 side.
 */
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final AccessDenialAuditor accessDenialAuditor;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.UNAUTHORIZED.value())
                .error("Unauthorized")
                .message("Authentication required")
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        response.getWriter().write(MAPPER.writeValueAsString(body));
        accessDenialAuditor.recordUnauthorized(request, "no valid authentication");
    }
}
