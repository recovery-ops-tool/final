package com.recoverpro.server.config;

import com.recoverpro.server.security.AccessDenialAuditor;
import com.recoverpro.server.security.MigratingPasswordEncoder;
import com.recoverpro.server.security.RestAccessDeniedHandler;
import com.recoverpro.server.security.RestAuthenticationEntryPoint;
import com.recoverpro.server.security.jwt.JwtAuthenticationFilter;
import com.recoverpro.server.security.jwt.JwtTokenProvider;
import com.recoverpro.server.security.jwt.SseTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
        "/api/v1/auth/**",
        "/api/v1/contact",
        "/api/v1/webhooks/stripe/**",
        "/api/v1/webhooks/razorpay/**",
        "/p/**",
        // SYSTEM 05 TASK 5.2: wildcarded so /actuator/health/liveness and /actuator/health/readiness
        // (the probe sub-paths Spring Boot exposes when probes are enabled) are covered too -- an
        // exact-match "/actuator/health" would 401 the load balancer's liveness/readiness checks.
        "/actuator/health/**",
        // SYSTEM 02 TASK 2.3: a Prometheus scraper has no way to carry this app's JWT, the same
        // reasoning SYSTEM 05 TASK 5.2.d already applies to /actuator/health ("load balancers
        // cannot authenticate"). Network-level restriction (only the scraper's IP/ingress rule
        // can reach this path) is the deployment's job, not Spring Security's -- see
        // docs/DB-HOTSPOTS.md.
        "/actuator/prometheus",
        // SYSTEM 06 TASK 6.4.c: deploy tooling (and anyone tracing "what commit is actually
        // live") needs this reachable the same way load balancers need /actuator/health --
        // build/git metadata isn't secret (it's already visible to anyone with repo access) and
        // doesn't warrant gating behind a JWT.
        "/actuator/info",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        // WebSocket upgrade requests are authenticated by JwtHandshakeInterceptor at the
        // handshake layer instead (it validates the JWT and rejects unauthenticated connections
        // with 401 before the handler ever sees them) -- letting the standard authenticated-request
        // rule below apply here too would 403 the upgrade before that interceptor even runs, since
        // JwtAuthenticationFilter doesn't understand the ?token= query param WS clients use.
        "/ws/**"
    };

    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsService userDetailsService;
    private final StringRedisTemplate redisTemplate;
    private final SseTicketService sseTicketService;
    private final AccessDenialAuditor accessDenialAuditor;

    @Value("${app.cors.allowed-origins:}")
    private List<String> allowedOrigins;

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(
                jwtTokenProvider, userDetailsService, redisTemplate, sseTicketService, accessDenialAuditor);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Idempotency-Key", "X-Device-Id"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint(new RestAuthenticationEntryPoint(accessDenialAuditor))
                .accessDeniedHandler(new RestAccessDeniedHandler(accessDenialAuditor)))
            .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
            // SYSTEM 07 TASK 7.1: X-Content-Type-Options/X-Frame-Options are already Spring
            // Security defaults (confirmed by curling a live instance) -- everything below is
            // what isn't. The web SPA's own security posture is set by web/nginx.conf (the layer
            // that actually serves the HTML document the browser renders, already comprehensively
            // configured with an ENFORCED CSP tuned against the real app -- fonts, map tiles, S3
            // images, same-origin API/WS via the nginx proxy); this backend-side config is
            // defense-in-depth for API/error responses and the (prod-disabled) Swagger UI page.
            .headers(headers -> headers
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(63072000L)
                    .preload(true))
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                // Note: permissionsPolicy(Customizer) returns PermissionsPolicyConfig itself (for
                // further chaining via .and()), not HeadersConfigurer -- permissionsPolicyHeader
                // is the variant that returns to HeadersConfigurer so this chain can continue.
                .permissionsPolicyHeader(permissions -> permissions
                    .policy("geolocation=(), microphone=(), camera=(), payment=(), usb=(), "
                            + "magnetometer=(), gyroscope=(), interest-cohort=()"))
                // Report-only, unlike nginx's enforced policy above -- this layer only ever
                // serves JSON (which doesn't execute scripts/styles regardless) plus, in
                // non-prod only, Swagger UI's own page. Without live traffic evidence for what
                // Swagger UI's bundled assets actually need (the way nginx's CSP was tuned
                // against the real SPA), enforcing blind risks breaking that dev tool for no
                // real security gain on pure-JSON responses.
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'self'; frame-ancestors 'none'; base-uri 'self'")
                    .reportOnly()));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder(@Value("${app.security.bcrypt-strength:12}") int bcryptStrength) {
        return new MigratingPasswordEncoder(bcryptStrength);
    }
}
