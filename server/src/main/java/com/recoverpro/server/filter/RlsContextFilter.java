package com.recoverpro.server.filter;

import com.recoverpro.server.security.RlsOrgIdHolder;
import com.recoverpro.server.security.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Registered as a plain servlet-container filter (like IdempotencyFilter),
 * so it runs after Spring Security's own internal chain (FilterChainProxy is
 * registered at order -100) has already authenticated the request — the
 * SecurityContext is populated by the time this filter reads it, and it
 * runs before the DispatcherServlet dispatches to any controller/repository
 * call, so RlsOrgIdHolder is always set before RlsAwareDataSource needs it.
 *
 * SYSTEM 13 TASK 13.1.b's prerequisite (SYSTEM 11 "MDC context") named orgId/userId in MDC as
 * already done -- it wasn't: only {@link RequestLoggingFilter}'s requestId was ever put there
 * (and under the wrong key, "reqId" instead of the "requestId" src/main/resources/logback-spring.xml's
 * prod JSON encoder has always expected -- fixed alongside this); this class only set {@link
 * RlsOrgIdHolder} (a separate ThreadLocal for RLS, not MDC). Every log line and, once configured,
 * every error-tracker event needs org/user correlation just as much as request correlation does --
 * added here since org id is what this filter already resolves. Both keys ride the same {@code
 * AsyncConfig.mdcPropagatingDecorator()} propagation as requestId already does (it copies the
 * whole MDC context map, not one named key).
 */
@Component
@Order(2)
public class RlsContextFilter extends OncePerRequestFilter {

    public static final String ORG_ID_MDC_KEY = "orgId";
    public static final String USER_ID_MDC_KEY = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
                RlsOrgIdHolder.set(principal.getOrganizationId());
                if (principal.getOrganizationId() != null) {
                    MDC.put(ORG_ID_MDC_KEY, principal.getOrganizationId().toString());
                }
                if (principal.getId() != null) {
                    MDC.put(USER_ID_MDC_KEY, principal.getId().toString());
                }
            }
            chain.doFilter(request, response);
        } finally {
            RlsOrgIdHolder.clear();
            MDC.remove(ORG_ID_MDC_KEY);
            MDC.remove(USER_ID_MDC_KEY);
        }
    }
}
