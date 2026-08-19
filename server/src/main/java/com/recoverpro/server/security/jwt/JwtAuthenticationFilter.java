package com.recoverpro.server.security.jwt;

import com.recoverpro.server.security.AccessDenialAuditor;
import com.recoverpro.server.security.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX    = "Bearer ";
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsService userDetailsService;
    private final StringRedisTemplate redisTemplate;
    private final SseTicketService sseTicketService;
    private final AccessDenialAuditor accessDenialAuditor;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isSseTicketRequest(request)) {
            authenticateViaTicket(request, response, filterChain);
            return;
        }

        String token = extractToken(request);

        if (!StringUtils.hasText(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!jwtTokenProvider.validateToken(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        BlacklistCheck blacklistCheck = checkBlacklist(token);
        if (blacklistCheck == BlacklistCheck.CHECK_FAILED) {
            log.error("JWT revocation check unavailable (Redis error) - failing closed, rejecting request from {}",
                    clientIp(request));
            writeServiceUnavailable(response, "Unable to verify token status. Retry shortly.");
            return;
        }
        if (blacklistCheck == BlacklistCheck.BLACKLISTED) {
            log.warn("Blocked request with blacklisted JWT from {}", clientIp(request));
            writeUnauthorized(request, response, "Token has been revoked");
            return;
        }

        try {
            String username = jwtTokenProvider.extractUsername(token);
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            if (userDetails instanceof UserPrincipal principal && !principal.isOrganizationActive()) {
                log.warn("Blocked request from suspended-organization user {}", principal.getId());
                writeOrganizationSuspended(request, response, principal.getId());
                return;
            }

            var authentication = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);

        } catch (UsernameNotFoundException e) {
            log.warn("User not found for token: {}", e.getMessage());
            writeUnauthorized(request, response, "Invalid credentials");
        } catch (Exception e) {
            log.error("Authentication error from {}: {}", clientIp(request), e.getMessage(), e);
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Authentication processing failed\",\"status\":500}");
            }
        }
    }

    // Browser EventSource cannot set custom headers, so the SSE notification stream is the one
    // endpoint that authenticates via a query param instead of the Authorization header -- but
    // it's a short-lived, single-use ticket (see SseTicketService), never the JWT itself, so
    // nothing sensitive ends up sitting in access logs/proxy logs/browser history.
    private static final String SSE_STREAM_PATH = "/api/v1/notifications/stream";

    private boolean isSseTicketRequest(HttpServletRequest request) {
        return SSE_STREAM_PATH.equals(request.getRequestURI())
                && StringUtils.hasText(request.getParameter("ticket"));
    }

    private void authenticateViaTicket(HttpServletRequest request, HttpServletResponse response,
                                       FilterChain filterChain) throws ServletException, IOException {
        String username = sseTicketService.redeemTicket(request.getParameter("ticket"));
        if (username == null) {
            writeUnauthorized(request, response, "Invalid or expired stream ticket");
            return;
        }
        try {
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            if (userDetails instanceof UserPrincipal principal && !principal.isOrganizationActive()) {
                writeOrganizationSuspended(request, response, principal.getId());
                return;
            }
            var authentication = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (UsernameNotFoundException e) {
            writeUnauthorized(request, response, "Invalid credentials");
        }
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private enum BlacklistCheck { CLEAR, BLACKLISTED, CHECK_FAILED }

    private BlacklistCheck checkBlacklist(String token) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + token))
                    ? BlacklistCheck.BLACKLISTED
                    : BlacklistCheck.CLEAR;
        } catch (Exception e) {
            log.error("Redis blacklist check failed: {}", e.getMessage());
            return BlacklistCheck.CHECK_FAILED;
        }
    }

    // SYSTEM 09 TASK 9.4.b: these three rejections short-circuit before Spring Security's own
    // exception translation ever runs (they return here directly instead of throwing or calling
    // filterChain.doFilter), so RestAuthenticationEntryPoint never sees them -- without auditing
    // here too, a blacklisted-token replay or a token for a since-deleted user would be a 401
    // with zero audit trail, the same gap 9.4.b exists to close.
    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"" + message + "\",\"status\":401}");
        }
        accessDenialAuditor.recordUnauthorized(request, message);
    }

    // SYSTEM 18 TASK 18.2.b: 403, not 401 -- the token is valid and belongs to a real, enabled
    // account; access is denied because the org it belongs to is suspended, not because the
    // caller failed to authenticate. Mirrors AccountDisabledException's FORBIDDEN semantics.
    private void writeOrganizationSuspended(HttpServletRequest request, HttpServletResponse response,
                                            java.util.UUID actorUserId) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Organization Suspended\","
                    + "\"message\":\"Your organization's access has been suspended. Contact your administrator.\","
                    + "\"status\":403}");
        }
        accessDenialAuditor.recordAccessDenied(request, actorUserId, "Organization suspended");
    }

    private void writeServiceUnavailable(HttpServletResponse response, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"" + message + "\",\"status\":503}");
        }
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
