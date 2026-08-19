package com.recoverpro.server.service;

import com.recoverpro.server.dto.request.AssignRoleRequest;
import com.recoverpro.server.dto.request.CreateUserRequest;
import com.recoverpro.server.dto.request.UpdateUserRequest;
import com.recoverpro.server.dto.response.PageResponse;
import com.recoverpro.server.dto.response.UserPermissionsResponse;
import com.recoverpro.server.dto.response.UserResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface UserService {

    UserResponse getUserById(UUID id);

    UserResponse getCurrentUser(UUID userId);

    PageResponse<UserResponse> listUsers(UUID callerOrgId, Pageable pageable);

    UserResponse getOrgUser(UUID callerOrgId, UUID targetUserId);

    UserResponse createUser(UUID callerOrgId, CreateUserRequest request);

    UserResponse updateUser(UUID callerOrgId, UUID targetUserId, UpdateUserRequest request);

    UserResponse assignRole(UUID callerOrgId, UUID targetUserId, AssignRoleRequest request);

    UserResponse removeRole(UUID callerOrgId, UUID targetUserId, String roleName);

    void enableUser(UUID callerOrgId, UUID targetUserId);

    void disableUser(UUID callerOrgId, UUID targetUserId);

    void deleteUser(UUID callerOrgId, UUID targetUserId);

    /** SYSTEM 18 TASK 18.3: users who have never completed onboarding (no successful login yet,
     *  still on the server-generated password from creation) -- the "pending invite" list. */
    List<UserResponse> listPendingInvites(UUID callerOrgId);

    /** Re-sends the welcome OTP, invalidating any earlier one. Only valid while the invite is
     *  still pending (see {@link #listPendingInvites}); rejects once the user has logged in. */
    void resendInvite(UUID callerOrgId, UUID targetUserId);

    /** Revokes a still-pending invite: disables the account and invalidates any outstanding OTP
     *  so it can no longer be redeemed. Rejects once the user has already completed onboarding --
     *  use {@link #disableUser} for an active account instead. */
    void revokeInvite(UUID callerOrgId, UUID targetUserId);

    /** SYSTEM 18 TASK 18.4: GDPR erasure for a verified data-subject request. Scrubs PII on the
     *  {@code users} row itself (same fields {@link #deleteUser} already scrubs, plus the MFA
     *  secret) and every other table known to hold a denormalized copy of this user's identity
     *  (see docs/PRIVACY.md for the full list and the audit-trail tombstoning position). */
    void eraseUserData(UUID callerOrgId, UUID targetUserId, String reason);

    List<UserResponse> listUsersByRole(UUID callerOrgId, String roleName);

    UserPermissionsResponse getUserPermissions(UUID callerOrgId, UUID targetUserId);

    UserPermissionsResponse grantDirectPermission(UUID callerOrgId, UUID targetUserId,
                                                   String permissionName, UUID grantedByUserId);

    UserPermissionsResponse revokeDirectPermission(UUID callerOrgId, UUID targetUserId,
                                                    String permissionName);
}
