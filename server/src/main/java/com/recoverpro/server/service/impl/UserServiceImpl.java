package com.recoverpro.server.service.impl;

import com.recoverpro.server.common.exception.BusinessException;
import com.recoverpro.server.common.exception.ResourceNotFoundException;
import com.recoverpro.server.config.AppProperties;
import com.recoverpro.server.config.PlatformConstants;
import com.recoverpro.server.dto.request.AssignRoleRequest;
import com.recoverpro.server.dto.request.CreateUserRequest;
import com.recoverpro.server.dto.request.UpdateUserRequest;
import com.recoverpro.server.dto.response.DirectPermissionResponse;
import com.recoverpro.server.dto.response.PageResponse;
import com.recoverpro.server.dto.response.PermissionResponse;
import com.recoverpro.server.dto.response.UserPermissionsResponse;
import com.recoverpro.server.dto.response.UserResponse;
import com.recoverpro.server.entity.Permission;
import com.recoverpro.server.entity.PasswordResetToken;
import com.recoverpro.server.entity.Role;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.entity.UserPermission;
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
import com.recoverpro.server.enums.AuditAction;
import com.recoverpro.server.enums.AuditResourceType;
import com.recoverpro.server.security.CustomUserDetailsService;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.AuditEventRequest;
import com.recoverpro.server.service.AuditService;
import com.recoverpro.server.service.EntitlementService;
import com.recoverpro.server.service.UserActionAuditService;
import com.recoverpro.server.service.EmailService;
import com.recoverpro.server.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserPermissionRepository userPermissionRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserActionAuditService auditLogService;
    private final AuditService auditService;
    private final EntitlementService entitlementService;
    private final EmailService emailService;
    private final AppProperties appProperties;
    private final CustomUserDetailsService customUserDetailsService;
    // SYSTEM 18 TASK 18.4: erasure-only dependencies -- each repository backs one denormalized-PII
    // location eraseUserData() scrubs beyond the users row itself.
    private final PtpHistoryRepository ptpHistoryRepository;
    private final PtpRepository ptpRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final UserCreationRequestRepository userCreationRequestRepository;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID id) {
        return userRepository.findById(id)
                .map(userMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(UUID userId) {
        return getUserById(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> listUsers(UUID callerOrgId, Pageable pageable) {
        Page<User> page = callerOrgId == null
                ? userRepository.findAllByOrderByCreatedAtDesc(pageable)
                : userRepository.findByOrganizationIdOrderByCreatedAtDesc(callerOrgId, pageable);
        return PageResponse.of(page.map(userMapper::toResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getOrgUser(UUID callerOrgId, UUID targetUserId) {
        return userMapper.toResponse(requireSameOrg(callerOrgId, targetUserId));
    }

    @Override
    public UserResponse createUser(UUID callerOrgId, CreateUserRequest request) {
        if (callerOrgId == null) throw new BusinessException("Caller has no organization context");

        String email = request.getEmail().toLowerCase().trim();
        if (userRepository.existsByEmail(email)) {
            log.warn("Duplicate user creation attempt email hash={}", email.hashCode());
            throw new BusinessException("A user with that email already exists");
        }
        if (!entitlementService.canCreateUser(callerOrgId)) {
            throw new BusinessException(
                    "User limit reached for this organization's plan. Upgrade your plan or contact support.");
        }

        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(UUID.randomUUID() + UUID.randomUUID().toString()))
                .firstName(request.getFirstName().strip())
                .lastName(request.getLastName().strip())
                .enabled(true)
                .accountLocked(false)
                .organizationId(callerOrgId)
                .build();

        if (request.getRoleNames() != null && !request.getRoleNames().isEmpty()) {
            // Widened beyond Org/Platform Admin to MANAGER/TL by CAN_CREATE_USER (see
            // UserController) -- a caller creating a user must not be able to grant a role
            // carrying more authority than the caller itself holds. Mirrors assignRole()'s
            // guard below, applied here too since assignRole() only protects existing users.
            User caller = currentCallerOrThrow(callerOrgId);
            boolean callerIsPlatformAdmin = isPlatformAdmin(caller);
            Set<String> callerPerms = collectPermissions(caller);

            for (String name : request.getRoleNames()) {
                String normalized = name.toUpperCase();
                Role role = roleRepository.findByNameAndOrganizationIdIsNull(normalized)
                        .or(() -> roleRepository.findByNameAndOrganizationId(normalized, callerOrgId))
                        .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + name));
                if (PlatformConstants.ROLE_PLATFORM_ADMIN.equals(role.getName()) && !callerIsPlatformAdmin) {
                    throw new BusinessException("ROLE_PLATFORM_ADMIN cannot be assigned by an Org Admin");
                }
                Set<String> rolePerms = role.getPermissions().stream()
                        .map(Permission::getName).collect(Collectors.toSet());
                if (!callerPerms.containsAll(rolePerms)) {
                    Set<String> missing = new HashSet<>(rolePerms);
                    missing.removeAll(callerPerms);
                    throw new BusinessException("You cannot assign a role with permissions you don't hold: " + missing);
                }
                user.addRole(role);
            }
        }

        User saved = userRepository.save(user);
        sendWelcomeOtp(saved);
        auditLogService.logUserAction(callerId(), "USER_CREATED",
                "Created user id=" + saved.getId() + " in org=" + callerOrgId);
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.USER_CREATED)
                .resourceType(AuditResourceType.USER)
                .resourceId(String.valueOf(saved.getId()))
                .build());
        return userMapper.toResponse(saved);
    }

    private void sendWelcomeOtp(User user) {
        int expiryMinutes = appProperties.getSecurity().getWelcomeOtpExpiryMinutes();
        String otp = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        passwordResetTokenRepository.invalidateAllByUserId(user.getId());
        PasswordResetToken token = PasswordResetToken.builder()
                .user(user)
                .otpHash(passwordEncoder.encode(otp))
                .expiresAt(Instant.now().plus(expiryMinutes, ChronoUnit.MINUTES))
                .build();
        passwordResetTokenRepository.save(token);
        emailService.sendWelcomeEmail(user.getEmail(), user.getFirstName(), otp, expiryMinutes);
    }

    @Override
    public UserResponse updateUser(UUID callerOrgId, UUID targetUserId, UpdateUserRequest request) {
        User user = requireSameOrg(callerOrgId, targetUserId);
        String originalEmail = user.getEmail();
        if (request.getFirstName() != null && !request.getFirstName().isBlank()) {
            user.setFirstName(request.getFirstName().strip());
        }
        if (request.getLastName() != null && !request.getLastName().isBlank()) {
            user.setLastName(request.getLastName().strip());
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            String newEmail = request.getEmail().strip().toLowerCase();
            userRepository.findByEmailIgnoreCase(newEmail)
                    .filter(existing -> !existing.getId().equals(targetUserId))
                    .ifPresent(__ -> { throw new BusinessException("Email is already in use"); });
            user.setEmail(newEmail);
        }
        User saved = userRepository.save(user);
        auditLogService.logUserAction(callerId(), "USER_UPDATED", "Updated user id=" + targetUserId);
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.USER_UPDATED)
                .resourceType(AuditResourceType.USER)
                .resourceId(targetUserId.toString())
                .build());
        evictUserCacheAfterCommit(originalEmail);
        evictUserCacheAfterCommit(saved.getEmail());
        return userMapper.toResponse(saved);
    }

    @Override
    public UserResponse assignRole(UUID callerOrgId, UUID targetUserId, AssignRoleRequest request) {
        User caller = currentCallerOrThrow(callerOrgId);
        User target = requireSameOrg(callerOrgId, targetUserId);
        String normalizedRoleName = request.getRoleName().toUpperCase();

        Role role = roleRepository.findByNameAndOrganizationIdIsNull(normalizedRoleName)
                .or(() -> callerOrgId != null
                        ? roleRepository.findByNameAndOrganizationId(normalizedRoleName, callerOrgId)
                        : Optional.empty())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + request.getRoleName()));

        Set<String> callerPerms = collectPermissions(caller);
        Set<String> rolePerms   = role.getPermissions().stream()
                .map(Permission::getName).collect(Collectors.toSet());
        if (!callerPerms.containsAll(rolePerms)) {
            Set<String> missing = new HashSet<>(rolePerms);
            missing.removeAll(callerPerms);
            throw new BusinessException("You cannot assign a role with permissions you don't hold: " + missing);
        }
        if (PlatformConstants.ROLE_PLATFORM_ADMIN.equals(role.getName()) && !isPlatformAdmin(caller)) {
            throw new BusinessException("ROLE_PLATFORM_ADMIN can only be granted by another Platform Admin");
        }

        target.addRole(role);
        User saved = userRepository.save(target);
        auditLogService.logUserAction(callerId(), "ROLE_GRANTED",
                "Granted " + role.getName() + " to user=" + targetUserId);
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.ROLE_GRANTED)
                .resourceType(AuditResourceType.USER)
                .resourceId(targetUserId.toString())
                .afterState(Map.of("role", role.getName()))
                .build());
        evictUserCacheAfterCommit(saved.getEmail());
        return userMapper.toResponse(saved);
    }

    @Override
    public UserResponse removeRole(UUID callerOrgId, UUID targetUserId, String roleName) {
        User target = requireSameOrg(callerOrgId, targetUserId);
        String normalized = roleName.toUpperCase();
        Role role = roleRepository.findByNameAndOrganizationIdIsNull(normalized)
                .or(() -> callerOrgId != null
                        ? roleRepository.findByNameAndOrganizationId(normalized, callerOrgId)
                        : Optional.empty())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleName));

        if (target.getRoles().size() <= 1) {
            throw new BusinessException("Cannot remove the user's last role -- user would become orphaned");
        }

        target.removeRole(role);
        User saved = userRepository.save(target);
        auditLogService.logUserAction(callerId(), "ROLE_REVOKED",
                "Revoked " + roleName + " from user=" + targetUserId);
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.ROLE_REVOKED)
                .resourceType(AuditResourceType.USER)
                .resourceId(targetUserId.toString())
                .beforeState(Map.of("role", role.getName()))
                .build());
        evictUserCacheAfterCommit(saved.getEmail());
        return userMapper.toResponse(saved);
    }

    @Override
    public void enableUser(UUID callerOrgId, UUID targetUserId) {
        User user = requireSameOrg(callerOrgId, targetUserId);
        user.setEnabled(true);
        user.setAccountLocked(false);
        user.setLockoutUntil(null);
        user.setFailedLoginAttempts(0);
        userRepository.save(user);
        auditLogService.logUserAction(callerId(), "USER_ENABLED", "Enabled user id=" + targetUserId);
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.USER_REACTIVATED)
                .resourceType(AuditResourceType.USER)
                .resourceId(targetUserId.toString())
                .build());
        evictUserCacheAfterCommit(user.getEmail());
    }

    @Override
    public void disableUser(UUID callerOrgId, UUID targetUserId) {
        User user = requireSameOrg(callerOrgId, targetUserId);
        requireNotSelf(targetUserId, "deactivate");
        requireNotLastPlatformAdmin(user, "deactivate");
        user.setEnabled(false);
        userRepository.save(user);
        auditLogService.logUserAction(callerId(), "USER_DISABLED", "Disabled user id=" + targetUserId);
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.USER_DEACTIVATED)
                .resourceType(AuditResourceType.USER)
                .resourceId(targetUserId.toString())
                .build());
        evictUserCacheAfterCommit(user.getEmail());
    }

    @Override
    public void deleteUser(UUID callerOrgId, UUID targetUserId) {
        User user = requireSameOrg(callerOrgId, targetUserId);
        requireNotSelf(targetUserId, "delete");
        requireNotLastPlatformAdmin(user, "delete");
        String originalEmail = user.getEmail();
        user.setEmail("deleted-" + user.getId() + "@recoverpro.internal");
        user.setFirstName("[Deleted]");
        user.setLastName("[User]");
        user.setEnabled(false);
        // SYSTEM 18 TASK 18.4.a: mfaSecret is a live TOTP seed, not covered by the name/email
        // scrub above -- a "deleted" account whose secret is still intact isn't actually scrubbed,
        // it just can't log in through the ordinary password path.
        user.setMfaSecret(null);
        user.setMfaEnabled(false);
        user.setDeletedAt(Instant.now());
        userRepository.save(user);
        auditLogService.logUserAction(callerId(), "USER_DELETED",
                "Soft-deleted user id=" + targetUserId + " in org=" + callerOrgId);
        // No dedicated USER_DELETED taxonomy entry -- deleteUser() is a soft-delete that also
        // disables the account, so it's recorded as a deactivation with deleted=true in metadata
        // rather than adding a near-duplicate action.
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.USER_DEACTIVATED)
                .resourceType(AuditResourceType.USER)
                .resourceId(targetUserId.toString())
                .metadata(Map.of("deleted", "true"))
                .build());
        evictUserCacheAfterCommit(originalEmail);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> listPendingInvites(UUID callerOrgId) {
        if (callerOrgId == null) return List.of();
        return userRepository.findPendingInvitesByOrganizationId(callerOrgId).stream()
                .map(userMapper::toResponse).toList();
    }

    @Override
    public void resendInvite(UUID callerOrgId, UUID targetUserId) {
        User user = requireSameOrg(callerOrgId, targetUserId);
        requirePendingInvite(user, "resend the invite for");
        sendWelcomeOtp(user);
        auditLogService.logUserAction(callerId(), "USER_INVITE_RESENT",
                "Resent invite for user id=" + targetUserId);
    }

    @Override
    public void revokeInvite(UUID callerOrgId, UUID targetUserId) {
        User user = requireSameOrg(callerOrgId, targetUserId);
        requireNotSelf(targetUserId, "revoke the invite for");
        requirePendingInvite(user, "revoke the invite for");
        user.setEnabled(false);
        userRepository.save(user);
        passwordResetTokenRepository.invalidateAllByUserId(user.getId());
        auditLogService.logUserAction(callerId(), "USER_INVITE_REVOKED",
                "Revoked pending invite for user id=" + targetUserId);
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.USER_DEACTIVATED)
                .resourceType(AuditResourceType.USER)
                .resourceId(targetUserId.toString())
                .metadata(Map.of("inviteRevoked", "true"))
                .build());
        evictUserCacheAfterCommit(user.getEmail());
    }

    /** SYSTEM 18 TASK 18.3.a: "pending" means never completed onboarding -- passwordChangedAt is
     *  NOT a usable signal (it defaults to the creation instant via @Builder.Default, so it is
     *  never actually null), but lastLoginAt only gets set by a real successful login, which is
     *  only possible after the user has redeemed their welcome OTP and set a real password. */
    private void requirePendingInvite(User user, String action) {
        if (user.getLastLoginAt() != null) {
            throw new BusinessException(
                    "Cannot " + action + " a user who has already completed onboarding");
        }
    }

    /**
     * SYSTEM 18 TASK 18.4: erasure path for a verified GDPR data-subject request, distinct from
     * {@link #deleteUser} (an ordinary, reversible-in-spirit offboarding). This scrubs the same
     * users-row fields deleteUser does, PLUS every other table known to hold a denormalized copy
     * of this user's identity (see docs/PRIVACY.md for the full inventory and the audit-trail
     * tombstoning position taken here). Sessions are killed outright rather than left to expire.
     */
    @Override
    public void eraseUserData(UUID callerOrgId, UUID targetUserId, String reason) {
        User user = requireSameOrg(callerOrgId, targetUserId);
        requireNotSelf(targetUserId, "erase");
        requireNotLastPlatformAdmin(user, "erase");

        String originalEmail = user.getEmail();
        String tombstoneEmail = "erased-" + user.getId() + "@recoverpro.internal";
        String tombstoneName = "[Erased]";

        user.setEmail(tombstoneEmail);
        user.setFirstName(tombstoneName);
        user.setLastName(tombstoneName);
        user.setEnabled(false);
        user.setMfaSecret(null);
        user.setMfaEnabled(false);
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID() + UUID.randomUUID().toString()));
        user.setDeletedAt(Instant.now());
        userRepository.save(user);

        passwordResetTokenRepository.invalidateAllByUserId(user.getId());

        ptpHistoryRepository.scrubChangedByName(user.getId(), tombstoneName);
        ptpRepository.scrubAgentName(user.getId(), tombstoneName);
        chatSessionRepository.scrubAgentFirstName(user.getId(), tombstoneName);
        userCreationRequestRepository.scrubByCreatedUserId(user.getId(), tombstoneEmail, tombstoneName);

        // unified_audit_events.actor_user_id is a bare FK to users(id), never a denormalized
        // name/email copy -- once the users row above is scrubbed, any audit query resolving that
        // FK already sees the tombstone. Nothing further to do there; see docs/PRIVACY.md.
        auditLogService.logUserAction(callerId(), "USER_DATA_ERASED",
                "Erased user id=" + targetUserId + " (GDPR data-subject request); reason=" + reason);
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.USER_DATA_ERASED)
                .resourceType(AuditResourceType.USER)
                .resourceId(targetUserId.toString())
                .reason(reason)
                .build());
        evictUserCacheAfterCommit(originalEmail);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> listUsersByRole(UUID callerOrgId, String roleName) {
        if (callerOrgId == null) return List.of();
        return userRepository.findByOrganizationIdAndRoleName(callerOrgId, roleName)
                .stream().map(userMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public UserPermissionsResponse getUserPermissions(UUID callerOrgId, UUID targetUserId) {
        return buildPermissionsResponse(requireSameOrg(callerOrgId, targetUserId));
    }

    @Override
    public UserPermissionsResponse grantDirectPermission(UUID callerOrgId, UUID targetUserId,
                                                          String permissionName, UUID grantedByUserId) {
        User target = requireSameOrg(callerOrgId, targetUserId);
        Permission perm = permissionRepository.findByName(permissionName)
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found: " + permissionName));

        User caller = currentCallerOrThrow(callerOrgId);
        Set<String> callerPerms = collectPermissions(caller);
        if (!callerPerms.contains(perm.getName())) {
            throw new BusinessException("You cannot grant a permission you don't hold: " + permissionName);
        }

        userPermissionRepository.findByUserIdAndPermissionId(target.getId(), perm.getId())
                .ifPresentOrElse(
                        existing -> existing.setGranted(true),
                        () -> {
                            User grantor = grantedByUserId != null
                                    ? userRepository.findById(grantedByUserId).orElse(null) : null;
                            userPermissionRepository.save(UserPermission.builder()
                                    .user(target)
                                    .permission(perm)
                                    .grantedBy(grantor)
                                    .isGranted(true)
                                    .build());
                        });

        auditLogService.logUserAction(callerId(), "PERMISSION_GRANTED",
                "Granted " + permissionName + " to user=" + targetUserId);
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.PERMISSION_GRANTED)
                .resourceType(AuditResourceType.USER)
                .resourceId(targetUserId.toString())
                .afterState(Map.of("permission", permissionName))
                .build());
        evictUserCacheAfterCommit(target.getEmail());
        return buildPermissionsResponse(userRepository.findById(target.getId()).orElse(target));
    }

    @Override
    public UserPermissionsResponse revokeDirectPermission(UUID callerOrgId, UUID targetUserId,
                                                           String permissionName) {
        User target = requireSameOrg(callerOrgId, targetUserId);
        Permission perm = permissionRepository.findByName(permissionName)
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found: " + permissionName));

        if (userPermissionRepository.findByUserIdAndPermissionId(target.getId(), perm.getId()).isEmpty()) {
            throw new ResourceNotFoundException("Direct permission not found for user: " + permissionName);
        }
        userPermissionRepository.deleteByUserIdAndPermissionId(target.getId(), perm.getId());

        auditLogService.logUserAction(callerId(), "PERMISSION_REVOKED",
                "Revoked direct permission " + permissionName + " from user=" + targetUserId);
        auditService.record(AuditEventRequest.builder()
                .action(AuditAction.PERMISSION_REVOKED)
                .resourceType(AuditResourceType.USER)
                .resourceId(targetUserId.toString())
                .beforeState(Map.of("permission", permissionName))
                .build());
        evictUserCacheAfterCommit(target.getEmail());
        return buildPermissionsResponse(target);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private UserPermissionsResponse buildPermissionsResponse(User user) {
        Set<PermissionResponse> fromRoles = user.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(userMapper::toPermissionResponse)
                .collect(Collectors.toSet());

        Set<DirectPermissionResponse> direct = userPermissionRepository
                .findActiveGrantsByUserId(user.getId(), Instant.now())
                .stream()
                .map(up -> {
                    Permission p = up.getPermission();
                    return DirectPermissionResponse.builder()
                            .id(up.getId())
                            .name(p.getName())
                            .resource(p.getResource())
                            .action(p.getAction())
                            .description(p.getDescription())
                            .scope(p.getScope() != null ? p.getScope().name() : null)
                            .granted(up.isGranted())
                            .grantedAt(up.getGrantedAt())
                            .build();
                })
                .collect(Collectors.toSet());

        return UserPermissionsResponse.builder().fromRoles(fromRoles).direct(direct).build();
    }

    private UUID callerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal up) {
            return up.getId();
        }
        return null;
    }

    private User currentCallerOrThrow(UUID callerOrgId) {
        if (callerOrgId == null) {
            User platform = new User();
            platform.setRoles(new HashSet<>());
            return platform;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal up) {
            return userRepository.findById(up.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Caller user not found"));
        }
        throw new BusinessException("No authenticated caller");
    }

    private User requireSameOrg(UUID callerOrgId, UUID targetUserId) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + targetUserId));
        if (callerOrgId != null
                && (user.getOrganizationId() == null || !user.getOrganizationId().equals(callerOrgId))) {
            throw new ResourceNotFoundException("User not found: " + targetUserId);
        }
        return user;
    }

    private Set<String> collectPermissions(User user) {
        if (user.getRoles().isEmpty()) {
            return roleRepository.findByName(PlatformConstants.ROLE_PLATFORM_ADMIN)
                    .map(r -> r.getPermissions().stream()
                            .map(Permission::getName).collect(Collectors.toSet()))
                    .orElse(Set.of());
        }
        Set<String> out = new HashSet<>();
        for (Role r : user.getRoles()) {
            for (Permission p : r.getPermissions()) {
                out.add(p.getName());
            }
        }
        return out;
    }

    private boolean isPlatformAdmin(User user) {
        return user.getRoles().stream()
                .anyMatch(r -> PlatformConstants.ROLE_PLATFORM_ADMIN.equals(r.getName()));
    }

    private void requireNotSelf(UUID targetUserId, String action) {
        if (targetUserId.equals(callerId())) {
            throw new BusinessException("You cannot " + action + " your own account");
        }
    }

    private void requireNotLastPlatformAdmin(User target, String action) {
        if (isPlatformAdmin(target)
                && userRepository.countByRoleNameAndEnabledTrue(PlatformConstants.ROLE_PLATFORM_ADMIN) <= 1) {
            throw new BusinessException("Cannot " + action + " the last active platform admin");
        }
    }

    /**
     * SYSTEM-PLAN 15.1: CustomUserDetailsService.evictUserCache() existed but was never called
     * from anywhere -- a revoked role or a deactivated user kept being honoured from the
     * "userDetails" cache (read on every authenticated request via JwtAuthenticationFilter ->
     * loadUserByUsername) until its TTL expired. Every method here that changes a user's
     * identity, roles, permissions, or active status must call this with the affected email(s).
     *
     * <p>Evicts AFTER commit, never inside the transaction: evicting first and then rolling back
     * leaves the cache technically correct but wastes work; more importantly, evicting before
     * commit lets a concurrent read on another thread repopulate the cache with the OLD value
     * before this transaction's write is even durable.
     *
     * <p>Cross-instance staleness: {@code evictUserCache()} clears both L1 (this instance's
     * Caffeine) and L2 (shared Redis) via {@link com.recoverpro.server.config.cache.TwoTierCache},
     * but a DIFFERENT instance's own L1 entry is untouched by that call -- there is no cross-
     * instance broadcast. Deliberately not adding Redis pub/sub for this: "userDetails" L1 is
     * already configured with a 30-second TTL (see RedisCacheConfig), which bounds every other
     * instance's worst-case staleness window to 30 seconds regardless of this fix. That is the
     * documented choice TASK 15.1.d asks for, not an oversight.
     */
    private void evictUserCacheAfterCommit(String email) {
        if (email == null || email.isBlank()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    customUserDetailsService.evictUserCache(email);
                }
            });
        } else {
            customUserDetailsService.evictUserCache(email);
        }
    }
}
