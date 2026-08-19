package com.recoverpro.server.service.impl;

import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.mapper.UserMapper;
import com.recoverpro.server.repository.ChatSessionRepository;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.PasswordResetTokenRepository;
import com.recoverpro.server.repository.PermissionRepository;
import com.recoverpro.server.repository.PtpHistoryRepository;
import com.recoverpro.server.repository.PtpRepository;
import com.recoverpro.server.repository.RoleRepository;
import com.recoverpro.server.repository.UserCreationRequestRepository;
import com.recoverpro.server.repository.UserPermissionRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.security.CustomUserDetailsService;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.EmailService;
import com.recoverpro.server.service.EntitlementService;
import com.recoverpro.server.service.UserActionAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-PLAN 15.1: exercises the REAL Spring caching proxy (unlike UserServiceImplTest, which
 * mocks CustomUserDetailsService and can only verify evictUserCache() was called, not that
 * eviction actually changes what the next lookup returns). This is the literal acceptance test
 * the task asks for: "deactivating a user immediately blocks their next request" -- proven by
 * checking UserDetails.isEnabled() before and after, through the same cached
 * loadUserByUsername() path JwtAuthenticationFilter calls on every request.
 *
 * <p>Same lightweight pattern as AgentContextServiceImplCachingTest: a plain
 * ConcurrentMapCacheManager instead of the real Redis-backed TwoTierCacheManager, so this needs
 * no Redis connection -- @EnableCaching's AOP proxy behaves identically either way for what's
 * being proven here (does an eviction happen, does the next read see fresh data).
 */
class UserServiceImplCacheEvictionTest {

    @Configuration
    // proxyTargetClass=true: matches Spring Boot's real default (AopAutoConfiguration sets
    // spring.aop.proxy-target-class=true unless overridden -- this app doesn't override it).
    // Plain @EnableCaching's own default (false) would JDK-proxy CustomUserDetailsService to
    // only its UserDetailsService interface, which then fails to satisfy UserServiceImpl's
    // constructor (typed to the concrete class, since evictUserCache() isn't on that interface)
    // -- a mismatch that would never surface outside a bare AnnotationConfigApplicationContext
    // like this one, but is worth getting right rather than masking with a workaround.
    @EnableCaching(proxyTargetClass = true)
    static class CachingTestConfig {
        @Bean UserRepository userRepository() { return mock(UserRepository.class); }
        @Bean RoleRepository roleRepository() { return mock(RoleRepository.class); }
        @Bean PermissionRepository permissionRepository() { return mock(PermissionRepository.class); }
        @Bean UserPermissionRepository userPermissionRepository() { return mock(UserPermissionRepository.class); }
        @Bean PasswordResetTokenRepository passwordResetTokenRepository() { return mock(PasswordResetTokenRepository.class); }
        @Bean UserMapper userMapper() { return mock(UserMapper.class); }
        @Bean PasswordEncoder passwordEncoder() { return mock(PasswordEncoder.class); }
        @Bean UserActionAuditService auditLogService() { return mock(UserActionAuditService.class); }
        @Bean AuditService auditService() { return mock(AuditService.class); }
        @Bean EntitlementService entitlementService() { return mock(EntitlementService.class); }
        @Bean EmailService emailService() { return mock(EmailService.class); }
        @Bean AppProperties appProperties() { return new AppProperties(); }
        @Bean OrganizationRepository organizationRepository() { return mock(OrganizationRepository.class); }
        @Bean PtpHistoryRepository ptpHistoryRepository() { return mock(PtpHistoryRepository.class); }
        @Bean PtpRepository ptpRepository() { return mock(PtpRepository.class); }
        @Bean ChatSessionRepository chatSessionRepository() { return mock(ChatSessionRepository.class); }
        @Bean UserCreationRequestRepository userCreationRequestRepository() { return mock(UserCreationRequestRepository.class); }

        @Bean
        org.springframework.cache.CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("userDetails");
        }

        @Bean
        CustomUserDetailsService customUserDetailsService(UserRepository userRepository,
                OrganizationRepository organizationRepository) {
            return new CustomUserDetailsService(userRepository, organizationRepository);
        }

        @Bean
        UserServiceImpl userServiceImpl(UserRepository userRepository, RoleRepository roleRepository,
                PermissionRepository permissionRepository, UserPermissionRepository userPermissionRepository,
                PasswordResetTokenRepository passwordResetTokenRepository, UserMapper userMapper,
                PasswordEncoder passwordEncoder, UserActionAuditService auditLogService, AuditService auditService,
                EntitlementService entitlementService, EmailService emailService, AppProperties appProperties,
                CustomUserDetailsService customUserDetailsService, PtpHistoryRepository ptpHistoryRepository,
                PtpRepository ptpRepository, ChatSessionRepository chatSessionRepository,
                UserCreationRequestRepository userCreationRequestRepository) {
            return new UserServiceImpl(userRepository, roleRepository, permissionRepository,
                    userPermissionRepository, passwordResetTokenRepository, userMapper, passwordEncoder,
                    auditLogService, auditService, entitlementService, emailService, appProperties,
                    customUserDetailsService, ptpHistoryRepository, ptpRepository, chatSessionRepository,
                    userCreationRequestRepository);
        }
    }

    private AnnotationConfigApplicationContext ctx;
    private UserRepository userRepository;
    private CustomUserDetailsService customUserDetailsService;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigApplicationContext(CachingTestConfig.class);
        userRepository = ctx.getBean(UserRepository.class);
        customUserDetailsService = ctx.getBean(CustomUserDetailsService.class);
        userService = ctx.getBean(UserServiceImpl.class);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void disableUser_evictsCache_soTheVeryNextAuthCheckReflectsIt() {
        UUID orgId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String email = "agent@example.com";
        User user = User.builder().id(targetId).organizationId(orgId).email(email).enabled(true).build();

        when(userRepository.findById(targetId)).thenReturn(Optional.of(user));
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        lenient().when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Warm the cache, exactly as JwtAuthenticationFilter would on an earlier request.
        UserDetails before = customUserDetailsService.loadUserByUsername(email);
        assertThat(before.isEnabled()).isTrue();

        // Still cached -- must NOT re-hit the repository (would fail loudly if findByEmail were
        // stubbed with a Mockito strict-stub expectation of exactly one call; asserted instead
        // via object identity, which a fresh DB-backed UserPrincipal would not share).
        UserDetails stillCached = customUserDetailsService.loadUserByUsername(email);
        assertThat(stillCached).isSameAs(before);

        userService.disableUser(orgId, targetId);
        assertThat(user.isEnabled()).isFalse(); // the underlying entity really changed

        UserDetails afterDisable = customUserDetailsService.loadUserByUsername(email);
        assertThat(afterDisable.isEnabled())
                .as("a disabled user must stop being served from a stale cache entry")
                .isFalse();
        assertThat(afterDisable).isNotSameAs(before);
    }

    @Test
    void assignRole_evictsCache_soTheVeryNextAuthCheckHasTheNewRole() {
        UUID orgId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String email = "manager@example.com";
        User user = User.builder().id(targetId).organizationId(orgId).email(email).enabled(true).build();

        when(userRepository.findById(targetId)).thenReturn(Optional.of(user));
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        lenient().when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RoleRepository roleRepository = ctx.getBean(RoleRepository.class);
        com.recoverpro.server.entity.Role role = com.recoverpro.server.entity.Role.builder()
                .name("MANAGER").permissions(new java.util.HashSet<>()).build();
        when(roleRepository.findByNameAndOrganizationIdIsNull("MANAGER")).thenReturn(Optional.of(role));

        UserDetails before = customUserDetailsService.loadUserByUsername(email);
        assertThat(before.getAuthorities()).noneMatch(a -> a.getAuthority().equals("MANAGER"));

        var request = new com.recoverpro.server.dto.request.AssignRoleRequest();
        request.setRoleName("MANAGER");
        // null callerOrgId -> currentCallerOrThrow() treats the caller as platform-admin-shaped
        // (no SecurityContextHolder setup needed) and requireSameOrg() skips its org check --
        // this test is only about cache eviction, not the permission-containment guard.
        userService.assignRole(null, targetId, request);

        UserDetails afterAssign = customUserDetailsService.loadUserByUsername(email);
        assertThat(afterAssign.getAuthorities()).anyMatch(a -> a.getAuthority().equals("MANAGER"));
    }

    /**
     * SYSTEM-PLAN 15.2: full audit of every @Cacheable/@CacheEvict/@CachePut in the codebase
     * (only 3 namespaces exist: userDetails, lucienContext, systemPrompts) found none are
     * actually vulnerable -- userDetails is keyed by email (globally unique, @Column(unique=true)
     * on User), lucienContext by sessionId (globally unique), and systemPrompts is a genuinely
     * platform-wide table with no organization_id column at all, not per-org data. This test
     * locks that in for userDetails specifically: two different orgs' users, cached back to back,
     * must never cross-contaminate.
     */
    @Test
    void loadUserByUsername_twoDifferentOrgsUsers_neverCrossContaminate() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        String emailA = "orga.user@example.com";
        String emailB = "orgb.user@example.com";
        User userA = User.builder().id(UUID.randomUUID()).organizationId(orgA).email(emailA)
                .firstName("Alice").enabled(true).build();
        User userB = User.builder().id(UUID.randomUUID()).organizationId(orgB).email(emailB)
                .firstName("Bob").enabled(true).build();

        when(userRepository.findByEmail(emailA)).thenReturn(Optional.of(userA));
        when(userRepository.findByEmail(emailB)).thenReturn(Optional.of(userB));

        UserDetails detailsA = customUserDetailsService.loadUserByUsername(emailA);
        UserDetails detailsB = customUserDetailsService.loadUserByUsername(emailB);

        assertThat(detailsA.getUsername()).isEqualTo(emailA);
        assertThat(detailsB.getUsername()).isEqualTo(emailB);
        assertThat(detailsA).isNotSameAs(detailsB);

        // Re-fetching org A's user after org B's lookup must still return org A's own cached
        // entry, not org B's -- proves the cache key genuinely discriminates between tenants.
        UserDetails detailsAAgain = customUserDetailsService.loadUserByUsername(emailA);
        assertThat(detailsAAgain).isSameAs(detailsA);
        assertThat(detailsAAgain.getUsername()).isEqualTo(emailA);
    }
}
