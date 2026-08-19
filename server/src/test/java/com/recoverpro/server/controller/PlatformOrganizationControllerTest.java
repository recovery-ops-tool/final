package com.recoverpro.server.controller;

import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.config.PlatformConstants;
import com.recoverpro.server.dto.request.CreateOrganizationRequest;
import com.recoverpro.server.entity.PasswordResetToken;
import com.recoverpro.server.entity.Role;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.mapper.UserMapper;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.OrgSubscriptionRepository;
import com.recoverpro.server.repository.PasswordResetTokenRepository;
import com.recoverpro.server.repository.RoleRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.security.CustomUserDetailsService;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.EmailService;
import com.recoverpro.server.service.FeatureFlagService;
import com.recoverpro.server.service.NotificationService;
import com.recoverpro.server.service.UserActionAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage: create() built the org-admin user and saved it, but never called
 * sendWelcomeOtp -- a fully-wired private method (email service, token repo, all injected) that
 * simply had no call site. Every other user-creation flow in the codebase (UserServiceImpl,
 * PlatformSetupServiceImpl, UserCreationRequestServiceImpl) sends this email; this one silently
 * didn't. Also covers a second bug found in the same dead method: it read
 * Security.otpExpiryMinutes (10 min, meant for password-reset OTPs) instead of
 * Security.welcomeOtpExpiryMinutes (1440 min) -- the other three call sites all use the latter.
 */
@ExtendWith(MockitoExtension.class)
class PlatformOrganizationControllerTest {

    @Mock private OrganizationRepository orgRepo;
    @Mock private UserRepository userRepo;
    @Mock private RoleRepository roleRepo;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserActionAuditService auditLogService;
    @Mock private AuditService auditService;
    @Mock private EmailService emailService;
    @Mock private PasswordResetTokenRepository passwordResetTokenRepo;
    @Mock private UserMapper userMapper;
    @Mock private NotificationService notificationService;
    @Mock private OrgSubscriptionRepository orgSubscriptionRepo;
    @Mock private FeatureFlagService featureFlagService;
    @Mock private CustomUserDetailsService customUserDetailsService;

    private AppProperties appProperties;
    private PlatformOrganizationController controller;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        controller = new PlatformOrganizationController(
                orgRepo, userRepo, roleRepo, passwordEncoder, auditLogService, auditService,
                emailService, passwordResetTokenRepo, appProperties, userMapper, notificationService,
                orgSubscriptionRepo, featureFlagService, customUserDetailsService);

        // lenient(): not every test below exercises the create() flow these were written for
        // (the delete/restore/setActive tests added for SYSTEM 18 TASK 18.2 don't touch most of
        // these), and MockitoExtension's default STRICT_STUBS would otherwise fail those tests
        // for "unnecessary stubbing" rather than for anything they actually got wrong.
        lenient().when(orgRepo.existsByCode(any())).thenReturn(false);
        lenient().when(orgRepo.existsByName(any())).thenReturn(false);
        lenient().when(userRepo.existsByEmail(any())).thenReturn(false);
        lenient().when(roleRepo.findByNameAndOrganizationIdIsNull(PlatformConstants.ROLE_ORG_ADMIN))
                .thenReturn(Optional.of(Role.builder().name(PlatformConstants.ROLE_ORG_ADMIN).build()));
        lenient().when(orgRepo.save(any())).thenAnswer(inv -> {
            com.recoverpro.server.entity.Organization o = inv.getArgument(0);
            if (o != null && o.getId() == null) o.setId(UUID.randomUUID()); // JPA @GeneratedValue, simulated
            return o;
        });
        lenient().when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(orgSubscriptionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        lenient().when(userRepo.countByOrganizationId(any())).thenReturn(1L);
        lenient().when(userRepo.findByOrganizationIdAndRoleName(any(), any())).thenReturn(java.util.List.of());
    }

    /**
     * SYSTEM-PLAN 28.1: create() previously created NO OrgSubscription row at all, which left a
     * new org with no FeatureFlag rows either -- RequiresFeatureAspect's fail-open default then
     * granted every paid feature, and EntitlementServiceImpl's "no limit row = unlimited" granted
     * unlimited users/loans. This asserts every org now gets a defined TRIAL subscription in the
     * SAME call, and that flags are provisioned for it (not left to some later event).
     */
    @Test
    void create_alwaysCreatesATrialSubscription_andProvisionsFlagsForIt() {
        CreateOrganizationRequest request = new CreateOrganizationRequest();
        request.setName("Delta Recovery");
        request.setCode("DELTA");
        request.setAdminEmail("admin@delta.test");
        request.setAdminFirstName("Dana");
        request.setAdminLastName("Admin");

        UserPrincipal caller = mock(UserPrincipal.class);
        when(caller.getId()).thenReturn(UUID.randomUUID());

        controller.create(request, caller);

        ArgumentCaptor<com.recoverpro.server.entity.OrgSubscription> subCaptor =
                ArgumentCaptor.forClass(com.recoverpro.server.entity.OrgSubscription.class);
        verify(orgSubscriptionRepo).save(subCaptor.capture());
        com.recoverpro.server.entity.OrgSubscription saved = subCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(com.recoverpro.server.entity.OrgSubscription.Status.TRIAL);
        assertThat(saved.getPlan()).isEqualTo(com.recoverpro.server.entity.OrgSubscription.Plan.STARTER);
        assertThat(saved.getTrialEndsAt()).isNotNull();
        assertThat(saved.getOrgId()).isNotNull();

        verify(featureFlagService).provisionFlagsFor(saved);
    }

    @Test
    void create_sendsWelcomeEmailWithCorrectExpiry() {
        CreateOrganizationRequest request = new CreateOrganizationRequest();
        request.setName("Acme Collections");
        request.setCode("ACME");
        request.setAdminEmail("admin@acme.test");
        request.setAdminFirstName("Ann");
        request.setAdminLastName("Admin");

        UserPrincipal caller = mock(UserPrincipal.class);
        when(caller.getId()).thenReturn(UUID.randomUUID());

        controller.create(request, caller);

        verify(emailService).sendWelcomeEmail(
                org.mockito.ArgumentMatchers.eq("admin@acme.test"),
                org.mockito.ArgumentMatchers.eq("Ann"),
                anyString(),
                org.mockito.ArgumentMatchers.eq(1440));

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepo).save(tokenCaptor.capture());
        assertThat(tokenCaptor.getValue().getUser().getEmail()).isEqualTo("admin@acme.test");
    }

    /**
     * SYSTEM-PLAN 18.1: CreateOrganizationRequest no longer has an adminPassword field at all --
     * the request DTO's shape now structurally guarantees the caller cannot set the initial
     * credential. This asserts the *generated* hash input is genuinely random (not fixed,
     * predictable, or empty), matching UserServiceImpl.createUser()'s established pattern.
     */
    @Test
    void create_generatesARandomInitialPassword_neverAFixedOrPredictableOne() {
        CreateOrganizationRequest request = new CreateOrganizationRequest();
        request.setName("Beta Recovery");
        request.setCode("BETA");
        request.setAdminEmail("admin@beta.test");
        request.setAdminFirstName("Bob");
        request.setAdminLastName("Admin");

        UserPrincipal caller = mock(UserPrincipal.class);
        when(caller.getId()).thenReturn(UUID.randomUUID());

        controller.create(request, caller);

        // encode() is called twice per create(): once for the admin's throwaway password hash,
        // once more inside sendWelcomeOtp() for the OTP hash -- in that order. Index 0 is the
        // password-hash input.
        ArgumentCaptor<String> hashedInput = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder, org.mockito.Mockito.times(2)).encode(hashedInput.capture());
        String firstOrgPasswordInput = hashedInput.getAllValues().get(0);
        assertThat(firstOrgPasswordInput).isNotBlank().hasSizeGreaterThan(20);

        // A second org's create() must produce a DIFFERENT random password-hash input -- proves
        // it's generated per-call, not a hardcoded placeholder that merely looks random once.
        when(userRepo.existsByEmail(any())).thenReturn(false);
        CreateOrganizationRequest secondRequest = new CreateOrganizationRequest();
        secondRequest.setName("Gamma Recovery");
        secondRequest.setCode("GAMMA");
        secondRequest.setAdminEmail("admin@gamma.test");
        secondRequest.setAdminFirstName("Gina");
        secondRequest.setAdminLastName("Admin");
        controller.create(secondRequest, caller);

        ArgumentCaptor<String> allHashedInputs = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder, org.mockito.Mockito.times(4)).encode(allHashedInputs.capture());
        String secondOrgPasswordInput = allHashedInputs.getAllValues().get(2);
        assertThat(secondOrgPasswordInput).isNotBlank().hasSizeGreaterThan(20);
        assertThat(firstOrgPasswordInput).isNotEqualTo(secondOrgPasswordInput);
    }

    // ─── SYSTEM 18 TASK 18.2.c: soft delete / restore ──────────────────────────

    @Test
    void delete_noUsersLeft_softDeletesInsteadOfHardDeleting() {
        UUID orgId = UUID.randomUUID();
        com.recoverpro.server.entity.Organization org = com.recoverpro.server.entity.Organization.builder()
                .id(orgId).name("Empty Org").code("EMPTY").isActive(true).build();
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
        when(userRepo.countByOrganizationId(orgId)).thenReturn(0L);
        when(userRepo.findEmailsByOrganizationId(orgId)).thenReturn(java.util.List.of());
        UserPrincipal caller = mock(UserPrincipal.class);
        when(caller.getId()).thenReturn(UUID.randomUUID());

        controller.delete(orgId, "no longer needed", caller);

        assertThat(org.isActive()).isFalse();
        assertThat(org.getDeletedAt()).isNotNull();
        assertThat(org.getDeletionReason()).isEqualTo("no longer needed");
        verify(orgRepo, never()).delete(any());
        verify(auditService).record(argThat(req ->
                req.getAction() == com.recoverpro.server.enums.AuditAction.ORG_DELETED));
    }

    @Test
    void delete_stillHasUsers_throwsAndDoesNotMutate() {
        UUID orgId = UUID.randomUUID();
        com.recoverpro.server.entity.Organization org = com.recoverpro.server.entity.Organization.builder()
                .id(orgId).name("Busy Org").code("BUSY").isActive(true).build();
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
        when(userRepo.countByOrganizationId(orgId)).thenReturn(3L);
        UserPrincipal caller = mock(UserPrincipal.class);

        assertThatThrownBy(() -> controller.delete(orgId, null, caller))
                .isInstanceOf(com.recoverpro.server.common.exception.BusinessException.class)
                .hasMessageContaining("3 user");
        assertThat(org.getDeletedAt()).isNull();
        verify(orgRepo, never()).save(any());
    }

    @Test
    void restore_deletedNotYetPurged_clearsDeletedAt() {
        UUID orgId = UUID.randomUUID();
        com.recoverpro.server.entity.Organization org = com.recoverpro.server.entity.Organization.builder()
                .id(orgId).name("Restorable Org").code("RESTORE").isActive(false)
                .deletedAt(java.time.Instant.now()).build();
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
        UserPrincipal caller = mock(UserPrincipal.class);
        when(caller.getId()).thenReturn(UUID.randomUUID());

        controller.restore(orgId, caller);

        assertThat(org.getDeletedAt()).isNull();
        assertThat(org.isActive())
                .as("restore reverses deletion but must not silently re-grant access -- use setActive separately")
                .isFalse();
    }

    @Test
    void restore_alreadyPurged_throws() {
        UUID orgId = UUID.randomUUID();
        com.recoverpro.server.entity.Organization org = com.recoverpro.server.entity.Organization.builder()
                .id(orgId).isActive(false).deletedAt(java.time.Instant.now())
                .purgedAt(java.time.Instant.now()).build();
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
        UserPrincipal caller = mock(UserPrincipal.class);

        assertThatThrownBy(() -> controller.restore(orgId, caller))
                .isInstanceOf(com.recoverpro.server.common.exception.BusinessException.class)
                .hasMessageContaining("purged");
    }

    @Test
    void setActive_suspending_evictsCachedUserDetailsForEveryOrgMember() {
        UUID orgId = UUID.randomUUID();
        com.recoverpro.server.entity.Organization org = com.recoverpro.server.entity.Organization.builder()
                .id(orgId).name("Org").code("ORG").isActive(true).build();
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
        when(userRepo.findEmailsByOrganizationId(orgId))
                .thenReturn(java.util.List.of("a@example.com", "b@example.com"));
        UserPrincipal caller = mock(UserPrincipal.class);
        when(caller.getId()).thenReturn(UUID.randomUUID());

        controller.setActive(orgId, false, caller);

        verify(customUserDetailsService).evictUserCache("a@example.com");
        verify(customUserDetailsService).evictUserCache("b@example.com");
    }
}
