package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.config.PlatformConstants;
import com.recoverpro.server.dto.request.CreateUserRequest;
import com.recoverpro.server.dto.request.UpdateUserRequest;
import com.recoverpro.server.dto.response.PageResponse;
import com.recoverpro.server.dto.response.UserResponse;
import com.recoverpro.server.entity.Permission;
import com.recoverpro.server.entity.Role;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.mapper.UserMapper;
import com.recoverpro.server.repository.ChatSessionRepository;
import com.recoverpro.server.repository.PasswordResetTokenRepository;
import com.recoverpro.server.repository.PermissionRepository;
import com.recoverpro.server.repository.PtpHistoryRepository;
import com.recoverpro.server.repository.PtpRepository;
import com.recoverpro.server.repository.RoleRepository;
import com.recoverpro.server.repository.UserCreationRequestRepository;
import com.recoverpro.server.repository.UserPermissionRepository;
import com.recoverpro.server.repository.UserRepository;
import com.recoverpro.server.security.CustomUserDetailsService;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.EntitlementService;
import com.recoverpro.server.service.UserActionAuditService;
import com.recoverpro.server.service.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PermissionRepository permissionRepository;
    @Mock private UserPermissionRepository userPermissionRepository;
    @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserActionAuditService auditLogService;
    @Mock private AuditService auditService;
    @Mock private EntitlementService entitlementService;
    @Mock private EmailService emailService;
    @Mock private AppProperties appProperties;
    @Mock private CustomUserDetailsService customUserDetailsService;
    @Mock private PtpHistoryRepository ptpHistoryRepository;
    @Mock private PtpRepository ptpRepository;
    @Mock private ChatSessionRepository chatSessionRepository;
    @Mock private UserCreationRequestRepository userCreationRequestRepository;

    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserServiceImpl(userRepository, roleRepository, permissionRepository,
                userPermissionRepository, passwordResetTokenRepository, userMapper, passwordEncoder,
                auditLogService, auditService, entitlementService, emailService, appProperties,
                customUserDetailsService, ptpHistoryRepository, ptpRepository, chatSessionRepository,
                userCreationRequestRepository);
        lenient().when(entitlementService.canCreateUser(any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void actAs(UUID userId) {
        User caller = User.builder().id(userId).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new UserPrincipal(caller), null, List.of()));
    }

    @Test
    void listUsers_orgScoped_delegatesPagingToRepository_notInMemory() {
        UUID orgId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        User user = new User();
        Page<User> repoPage = new PageImpl<>(List.of(user), pageable, 1);

        when(userRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId, pageable)).thenReturn(repoPage);
        when(userMapper.toResponse(user)).thenReturn(UserResponse.builder().build());

        PageResponse<UserResponse> result = service.listUsers(orgId, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        verify(userRepository, never()).findByOrganizationId(any());
    }

    @Test
    void listUsers_platformScoped_usesGlobalPagedQuery() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<User> repoPage = new PageImpl<>(List.of(), pageable, 0);
        when(userRepository.findAllByOrderByCreatedAtDesc(pageable)).thenReturn(repoPage);

        PageResponse<UserResponse> result = service.listUsers(null, pageable);

        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void updateUser_writesAuditLogEntry() {
        UUID orgId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User user = User.builder().id(targetId).organizationId(orgId).build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userMapper.toResponse(any())).thenReturn(UserResponse.builder().build());
        UpdateUserRequest request = new UpdateUserRequest();
        request.setFirstName("Updated");

        service.updateUser(orgId, targetId, request);

        verify(auditLogService).logUserAction(any(), eq("USER_UPDATED"), contains(targetId.toString()));
    }

    @Test
    void enableUser_writesAuditLogEntry() {
        UUID orgId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User user = User.builder().id(targetId).organizationId(orgId).build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.enableUser(orgId, targetId);

        verify(auditLogService).logUserAction(any(), eq("USER_ENABLED"), contains(targetId.toString()));
    }

    @Test
    void disableUser_writesAuditLogEntry() {
        UUID orgId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User user = User.builder().id(targetId).organizationId(orgId).build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        actAs(UUID.randomUUID());

        service.disableUser(orgId, targetId);

        verify(auditLogService).logUserAction(any(), eq("USER_DISABLED"), contains(targetId.toString()));
    }

    @Test
    void disableUser_targetingSelf_throwsAndDoesNotSave() {
        UUID selfId = UUID.randomUUID();
        User user = User.builder().id(selfId).build();
        when(userRepository.findById(selfId)).thenReturn(Optional.of(user));
        actAs(selfId);

        assertThatThrownBy(() -> service.disableUser(null, selfId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("own account");
        verify(userRepository, never()).save(any());
    }

    @Test
    void deleteUser_targetingSelf_throwsAndDoesNotSave() {
        UUID selfId = UUID.randomUUID();
        User user = User.builder().id(selfId).build();
        when(userRepository.findById(selfId)).thenReturn(Optional.of(user));
        actAs(selfId);

        assertThatThrownBy(() -> service.deleteUser(null, selfId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("own account");
        verify(userRepository, never()).save(any());
    }

    @Test
    void disableUser_lastEnabledPlatformAdmin_throwsWhenTargetedByAnotherAdmin() {
        UUID callerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Role platformAdminRole = Role.builder().name(PlatformConstants.ROLE_PLATFORM_ADMIN).build();
        User target = User.builder().id(targetId).enabled(true)
                .roles(new HashSet<>(Set.of(platformAdminRole))).build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.countByRoleNameAndEnabledTrue(PlatformConstants.ROLE_PLATFORM_ADMIN)).thenReturn(1L);
        actAs(callerId);

        assertThatThrownBy(() -> service.disableUser(null, targetId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("last active platform admin");
        verify(userRepository, never()).save(any());
    }

    @Test
    void deleteUser_lastEnabledPlatformAdmin_throwsWhenTargetedByAnotherAdmin() {
        UUID callerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Role platformAdminRole = Role.builder().name(PlatformConstants.ROLE_PLATFORM_ADMIN).build();
        User target = User.builder().id(targetId).enabled(true)
                .roles(new HashSet<>(Set.of(platformAdminRole))).build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.countByRoleNameAndEnabledTrue(PlatformConstants.ROLE_PLATFORM_ADMIN)).thenReturn(1L);
        actAs(callerId);

        assertThatThrownBy(() -> service.deleteUser(null, targetId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("last active platform admin");
        verify(userRepository, never()).save(any());
    }

    @Test
    void disableUser_oneOfTwoEnabledPlatformAdmins_succeeds() {
        UUID callerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Role platformAdminRole = Role.builder().name(PlatformConstants.ROLE_PLATFORM_ADMIN).build();
        User target = User.builder().id(targetId).enabled(true)
                .roles(new HashSet<>(Set.of(platformAdminRole))).build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.countByRoleNameAndEnabledTrue(PlatformConstants.ROLE_PLATFORM_ADMIN)).thenReturn(2L);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        actAs(callerId);

        service.disableUser(null, targetId);

        assertThat(target.isEnabled()).isFalse();
    }

    @Test
    void disableUser_orgAdmin_unaffectedByPlatformAdminGuardEvenAsSoleOrgAdmin() {
        UUID callerId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Role orgAdminRole = Role.builder().name(PlatformConstants.ROLE_ORG_ADMIN).build();
        User target = User.builder().id(targetId).organizationId(orgId).enabled(true)
                .roles(new HashSet<>(Set.of(orgAdminRole))).build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        actAs(callerId);

        service.disableUser(orgId, targetId);

        assertThat(target.isEnabled()).isFalse();
        verify(userRepository, never()).countByRoleNameAndEnabledTrue(anyString());
    }

    @Test
    void createUser_callerLacksTargetRolePermissions_throwsAndDoesNotSaveUser() {
        UUID orgId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();

        Permission userCreatePerm = Permission.builder().name("USER_CREATE").build();
        Permission userDeletePerm = Permission.builder().name("USER_DELETE").build();
        Role tlRole = Role.builder().name(PlatformConstants.ROLE_TL)
                .permissions(new HashSet<>(Set.of(userCreatePerm))).build();
        Role orgAdminRole = Role.builder().name(PlatformConstants.ROLE_ORG_ADMIN)
                .permissions(new HashSet<>(Set.of(userCreatePerm, userDeletePerm))).build();
        User caller = User.builder().id(callerId).organizationId(orgId)
                .roles(new HashSet<>(Set.of(tlRole))).build();

        actAs(callerId);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(roleRepository.findByNameAndOrganizationIdIsNull(PlatformConstants.ROLE_ORG_ADMIN))
                .thenReturn(Optional.of(orgAdminRole));

        CreateUserRequest request = new CreateUserRequest();
        request.setEmail("new.hire@example.com");
        request.setFirstName("New");
        request.setLastName("Hire");
        request.setRoleNames(Set.of(PlatformConstants.ROLE_ORG_ADMIN));

        assertThatThrownBy(() -> service.createUser(orgId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("permissions you don't hold");
        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_orgAtUserLimit_throwsAndDoesNotSaveUser() {
        UUID orgId = UUID.randomUUID();
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(entitlementService.canCreateUser(orgId)).thenReturn(false);

        CreateUserRequest request = new CreateUserRequest();
        request.setEmail("one.more@example.com");
        request.setFirstName("One");
        request.setLastName("More");

        assertThatThrownBy(() -> service.createUser(orgId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("limit reached");
        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_callerHoldsAllTargetRolePermissions_succeeds() {
        UUID orgId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();

        Permission userCreatePerm = Permission.builder().name("USER_CREATE").build();
        Role tlRole = Role.builder().name(PlatformConstants.ROLE_TL)
                .permissions(new HashSet<>(Set.of(userCreatePerm))).build();
        Role foRole = Role.builder().name(PlatformConstants.ROLE_FO)
                .permissions(new HashSet<>()).build();
        User caller = User.builder().id(callerId).organizationId(orgId)
                .roles(new HashSet<>(Set.of(tlRole))).build();

        actAs(callerId);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(roleRepository.findByNameAndOrganizationIdIsNull(PlatformConstants.ROLE_FO))
                .thenReturn(Optional.of(foRole));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userMapper.toResponse(any())).thenReturn(UserResponse.builder().build());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(appProperties.getSecurity()).thenReturn(new AppProperties.Security());

        CreateUserRequest request = new CreateUserRequest();
        request.setEmail("new.fo@example.com");
        request.setFirstName("New");
        request.setLastName("FO");
        request.setRoleNames(Set.of(PlatformConstants.ROLE_FO));

        service.createUser(orgId, request);

        verify(userRepository).save(any());
    }

    @Test
    void createUser_nonPlatformAdminAssignsPlatformAdminRole_throwsAndDoesNotSaveUser() {
        UUID orgId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();

        Role orgAdminRole = Role.builder().name(PlatformConstants.ROLE_ORG_ADMIN)
                .permissions(new HashSet<>()).build();
        Role platformAdminRole = Role.builder().name(PlatformConstants.ROLE_PLATFORM_ADMIN)
                .permissions(new HashSet<>()).build();
        User caller = User.builder().id(callerId).organizationId(orgId)
                .roles(new HashSet<>(Set.of(orgAdminRole))).build();

        actAs(callerId);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(roleRepository.findByNameAndOrganizationIdIsNull(PlatformConstants.ROLE_PLATFORM_ADMIN))
                .thenReturn(Optional.of(platformAdminRole));

        CreateUserRequest request = new CreateUserRequest();
        request.setEmail("wannabe.admin@example.com");
        request.setFirstName("Wannabe");
        request.setLastName("Admin");
        request.setRoleNames(Set.of(PlatformConstants.ROLE_PLATFORM_ADMIN));

        assertThatThrownBy(() -> service.createUser(orgId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ROLE_PLATFORM_ADMIN");
        verify(userRepository, never()).save(any());
    }

    // ─── SYSTEM 18 TASK 18.3: pending-invite lifecycle ─────────────────────────

    @Test
    void resendInvite_neverLoggedIn_resendsOtp() {
        UUID orgId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User user = User.builder().id(targetId).organizationId(orgId)
                .email("new.hire@example.com").firstName("New").lastLoginAt(null).build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(user));
        when(appProperties.getSecurity()).thenReturn(new com.recoverpro.server.config.AppProperties.Security());

        service.resendInvite(orgId, targetId);

        verify(emailService).sendWelcomeEmail(eq("new.hire@example.com"), eq("New"), anyString(), anyInt());
        verify(auditLogService).logUserAction(any(), eq("USER_INVITE_RESENT"), contains(targetId.toString()));
    }

    @Test
    void resendInvite_alreadyLoggedIn_throwsAndDoesNotSendEmail() {
        UUID orgId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User user = User.builder().id(targetId).organizationId(orgId)
                .lastLoginAt(java.time.Instant.now()).build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.resendInvite(orgId, targetId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already completed onboarding");
        verify(emailService, never()).sendWelcomeEmail(any(), any(), any(), anyInt());
    }

    @Test
    void revokeInvite_neverLoggedIn_disablesAndInvalidatesTokens() {
        UUID orgId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User user = User.builder().id(targetId).organizationId(orgId).enabled(true).lastLoginAt(null).build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        actAs(UUID.randomUUID());

        service.revokeInvite(orgId, targetId);

        assertThat(user.isEnabled()).isFalse();
        verify(passwordResetTokenRepository).invalidateAllByUserId(targetId);
        verify(auditLogService).logUserAction(any(), eq("USER_INVITE_REVOKED"), contains(targetId.toString()));
    }

    @Test
    void revokeInvite_alreadyLoggedIn_throwsAndDoesNotDisable() {
        UUID orgId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User user = User.builder().id(targetId).organizationId(orgId).enabled(true)
                .lastLoginAt(java.time.Instant.now()).build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(user));
        actAs(UUID.randomUUID());

        assertThatThrownBy(() -> service.revokeInvite(orgId, targetId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already completed onboarding");
        verify(userRepository, never()).save(any());
    }

    @Test
    void listPendingInvites_delegatesToRepositoryQuery() {
        UUID orgId = UUID.randomUUID();
        User pending = User.builder().id(UUID.randomUUID()).organizationId(orgId).build();
        when(userRepository.findPendingInvitesByOrganizationId(orgId)).thenReturn(List.of(pending));
        when(userMapper.toResponse(pending)).thenReturn(UserResponse.builder().build());

        List<UserResponse> result = service.listPendingInvites(orgId);

        assertThat(result).hasSize(1);
    }

    // ─── SYSTEM 18 TASK 18.4: GDPR erasure ──────────────────────────────────────

    @Test
    void eraseUserData_scrubsUsersRowAndEveryDenormalizedCopy() {
        UUID orgId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User user = User.builder().id(targetId).organizationId(orgId)
                .email("agent@example.com").firstName("Real").lastName("Name")
                .mfaEnabled(true).mfaSecret("totp-secret").enabled(true).build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenReturn("random-hash");
        actAs(UUID.randomUUID());

        service.eraseUserData(orgId, targetId, "Data subject request #123");

        assertThat(user.getEmail()).isEqualTo("erased-" + targetId + "@recoverpro.internal");
        assertThat(user.getFirstName()).isEqualTo("[Erased]");
        assertThat(user.getLastName()).isEqualTo("[Erased]");
        assertThat(user.getMfaSecret()).isNull();
        assertThat(user.isMfaEnabled()).isFalse();
        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getDeletedAt()).isNotNull();

        verify(ptpHistoryRepository).scrubChangedByName(eq(targetId), eq("[Erased]"));
        verify(ptpRepository).scrubAgentName(eq(targetId), eq("[Erased]"));
        verify(chatSessionRepository).scrubAgentFirstName(eq(targetId), eq("[Erased]"));
        verify(userCreationRequestRepository).scrubByCreatedUserId(
                eq(targetId), eq("erased-" + targetId + "@recoverpro.internal"), eq("[Erased]"));
        verify(passwordResetTokenRepository).invalidateAllByUserId(targetId);
        verify(auditService).record(argThat(req ->
                req.getAction() == com.recoverpro.server.enums.AuditAction.USER_DATA_ERASED));
    }

    @Test
    void eraseUserData_targetingSelf_throwsAndDoesNotScrub() {
        UUID selfId = UUID.randomUUID();
        User user = User.builder().id(selfId).build();
        when(userRepository.findById(selfId)).thenReturn(Optional.of(user));
        actAs(selfId);

        assertThatThrownBy(() -> service.eraseUserData(null, selfId, "self-request"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("own account");
        verify(userRepository, never()).save(any());
        verify(ptpHistoryRepository, never()).scrubChangedByName(any(), any());
    }
}
