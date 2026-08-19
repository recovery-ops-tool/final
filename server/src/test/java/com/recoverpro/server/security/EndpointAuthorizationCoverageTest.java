package com.recoverpro.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM 09 TASK 9.2: enumerates every handler method Spring MVC actually registered and asserts
 * each one either carries @PreAuthorize (method or declaring class) or appears in
 * {@link #PUBLIC_ENDPOINTS} below with a one-line justification. An endpoint with no annotation
 * is unreachable-by-default's opposite failure mode -- silently open -- so this exists to make
 * "forgot to annotate a new controller method" a build failure, not a production incident.
 */
// MOCK (the default web environment) sets up Spring MVC's handler-mapping infrastructure without
// binding a real port -- NONE does not register RequestMappingHandlerMapping at all.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class EndpointAuthorizationCoverageTest {

    // Two beans of this type exist -- Actuator's own controllerEndpointHandlerMapping is the
    // other -- this one is specifically the app's @RequestMapping controllers.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /**
     * Every entry here is a deliberate, reviewed exception -- identified by
     * "DeclaringSimpleClassName#methodName", not by path (a class/method identity survives a path
     * rename; a path string doesn't survive one). Each needs its own justification comment.
     */
    private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
            // Pre-authentication by definition -- these ARE how a caller becomes authenticated,
            // or explicitly support someone who by definition doesn't have a token yet.
            "AuthController#login",
            "AuthController#refreshToken",
            "AuthController#forgotPassword",
            "AuthController#verifyResetOtp",
            "AuthController#resetPassword",
            "AuthController#mfaVerifyLogin",
            "AuthController#googleLogin",
            // Public marketing/contact form -- no account exists yet by design, rate-limited
            // instead (ContactController, SYSTEM 07 TASK 7.3).
            "ContactController#submitContactForm",
            // Webhooks authenticate by provider signature verification inside the handler, not a
            // JWT -- Stripe/Razorpay have no way to carry this app's bearer token. Signature
            // checked in StripeWebhookService/RazorpayWebhookService before anything else runs.
            "StripeWebhookController#handle",
            "RazorpayWebhookController#handle",
            // Public payment-link resolution -- a borrower clicking a payment link sent by SMS/
            // email has no account/session; the link's own opaque token is the auth mechanism.
            "PaymentLinkResolveController#resolve",
            "PaymentLinkResolveController#pay",
            // Framework-provided infrastructure, not this app's own controllers -- can't require
            // a JWT without breaking the thing they exist for. BasicErrorController is Spring
            // Boot's own /error fallback (an auth failure rendering its own error page can't
            // itself require auth); the rest are springdoc-openapi's Swagger UI/OpenAPI-spec
            // endpoints, already disabled in prod (springdoc.swagger-ui.enabled=false,
            // springdoc.api-docs.enabled=false, application-prod.properties) and public-by-design
            // in every other profile (SecurityConfig.PUBLIC_PATHS already permits /v3/api-docs/**
            // and /swagger-ui/**).
            "BasicErrorController#error",
            "BasicErrorController#errorHtml",
            "SwaggerWelcomeWebMvc#redirectToUi",
            "OpenApiWebMvcResource#openapiJson",
            "OpenApiWebMvcResource#openapiYaml",
            "SwaggerConfigResource#openapiJson"
    );

    @Test
    void everyHandlerIsAnnotatedOrAllowlisted() {
        List<String> violations = new ArrayList<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            Method method = handler.getMethod();
            Class<?> declaringClass = method.getDeclaringClass();

            if (method.isAnnotationPresent(PreAuthorize.class)
                    || declaringClass.isAnnotationPresent(PreAuthorize.class)) {
                continue;
            }

            String identity = declaringClass.getSimpleName() + "#" + method.getName();
            if (PUBLIC_ENDPOINTS.contains(identity)) {
                continue;
            }

            violations.add(declaringClass.getName() + "#" + method.getName());
        }

        assertThat(violations)
                .as("Handler methods with no @PreAuthorize (method or class) and not in the "
                        + "PUBLIC_ENDPOINTS allowlist -- either annotate them or add a justified "
                        + "allowlist entry")
                .isEmpty();
    }
}
