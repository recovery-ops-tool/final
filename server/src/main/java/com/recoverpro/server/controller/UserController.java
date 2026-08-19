package com.recoverpro.server.controller;

import com.recoverpro.server.common.SafeSort;
import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.common.dto.response.PagedResponse;
import com.recoverpro.server.dto.request.AssignRoleRequest;
import com.recoverpro.server.dto.request.CreateUserRequest;
import com.recoverpro.server.dto.request.EraseUserRequest;
import com.recoverpro.server.dto.request.UpdateUserRequest;
import com.recoverpro.server.dto.response.UserPermissionsResponse;
import com.recoverpro.server.dto.response.UserResponse;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private static final String ADMIN_ROLES = Authz.LEADS;
    // Daily Dispatch and Field Agents (Manager/TL) both need to list field officers by role.
    private static final String LEADS_ROLES = Authz.LEADS;
    // A custom role granted USER_CREATE/USER_DELETE via Role Management should be able to use
    // these endpoints even without ORG_ADMIN/PLATFORM_ADMIN — UserPrincipal already flattens
    // granted role permissions into plain (non-ROLE_-prefixed) authorities at login.
    private static final String CAN_CREATE_USER = ADMIN_ROLES + " or hasAuthority('USER_CREATE')";
    private static final String CAN_DELETE_USER = ADMIN_ROLES + " or hasAuthority('USER_DELETE')";
    private static final String CAN_VIEW_USERS = LEADS_ROLES + " or hasAuthority('USER_CREATE') or hasAuthority('USER_DELETE')";

    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "createdAt", "createdAt",
            "email", "email",
            "firstName", "firstName",
            "lastName", "lastName");

    private final UserService userService;

    @GetMapping("/by-role/{roleName}")
    @PreAuthorize(LEADS_ROLES)
    public ResponseEntity<ApiResponse<List<UserResponse>>> listByRole(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String roleName) {
        String normalized = roleName.toUpperCase().startsWith("ROLE_")
                ? roleName.toUpperCase() : "ROLE_" + roleName.toUpperCase();
        return ResponseEntity.ok(ApiResponse.success(
                userService.listUsersByRole(callerOrg(principal), normalized)));
    }

    // SYSTEM 09 TASK 9.2: makes the pre-existing filter-chain authentication requirement
    // explicit -- self-service, principal.getId() throughout.
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserResponse>> getMyProfile(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(userService.getCurrentUser(principal.getId())));
    }

    @GetMapping("/{id}")
    @PreAuthorize(CAN_VIEW_USERS)
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(userService.getOrgUser(callerOrg(principal), id)));
    }

    @GetMapping
    @PreAuthorize(CAN_VIEW_USERS)
    public ResponseEntity<ApiResponse<PagedResponse<UserResponse>>> listUsers(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        int cappedSize = Math.min(Math.max(size, 1), 200);
        Sort sort = SafeSort.from(sortBy, sortDir, SORTABLE_FIELDS, "createdAt");
        var responsePage = userService.listUsers(callerOrg(principal), PageRequest.of(page, cappedSize, sort));
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.<UserResponse>builder()
                .content(responsePage.getContent())
                .page(responsePage.getPage())
                .size(responsePage.getSize())
                .totalElements(responsePage.getTotalElements())
                .totalPages(responsePage.getTotalPages())
                .last(responsePage.isLast())
                .build()));
    }

    @PostMapping
    @PreAuthorize(CAN_CREATE_USER)
    public ResponseEntity<ApiResponse<UserResponse>> createUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.createUser(principal.getOrganizationId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of("User created", created));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(ApiResponse.success(userService.updateUser(callerOrg(principal), id, request)));
    }

    @PostMapping("/{id}/roles")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<UserResponse>> assignRole(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody AssignRoleRequest request) {
        return ResponseEntity.ok(ApiResponse.success(userService.assignRole(callerOrg(principal), id, request)));
    }

    @DeleteMapping("/{id}/roles/{roleName}")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<UserResponse>> removeRole(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String roleName) {
        return ResponseEntity.ok(ApiResponse.success(userService.removeRole(callerOrg(principal), id, roleName)));
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<Void>> enableUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        userService.enableUser(callerOrg(principal), id);
        return ResponseEntity.ok(ApiResponse.of("User enabled", null));
    }

    @PatchMapping("/{id}/disable")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<Void>> disableUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        userService.disableUser(callerOrg(principal), id);
        return ResponseEntity.ok(ApiResponse.of("User disabled", null));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(CAN_DELETE_USER)
    public ResponseEntity<ApiResponse<Void>> deleteUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        userService.deleteUser(callerOrg(principal), id);
        return ResponseEntity.ok(ApiResponse.of("User deleted", null));
    }

    // SYSTEM 18 TASK 18.3: pending-invite lifecycle -- a "pending invite" is a created-but-never-
    // logged-in user (see UserServiceImpl#requirePendingInvite for why lastLoginAt, not
    // passwordChangedAt, is the reliable signal).
    @GetMapping("/pending-invites")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<List<UserResponse>>> listPendingInvites(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(userService.listPendingInvites(callerOrg(principal))));
    }

    @PostMapping("/{id}/invite/resend")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<Void>> resendInvite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        userService.resendInvite(callerOrg(principal), id);
        return ResponseEntity.ok(ApiResponse.of("Invite resent", null));
    }

    @DeleteMapping("/{id}/invite")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<Void>> revokeInvite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        userService.revokeInvite(callerOrg(principal), id);
        return ResponseEntity.ok(ApiResponse.of("Invite revoked", null));
    }

    // SYSTEM 18 TASK 18.4: GDPR erasure. Gated tighter than ordinary user management
    // (Authz.ADMINS, not ADMIN_ROLES/Authz.LEADS) -- a MANAGER/TL can deactivate or delete a
    // teammate's account, but an irreversible cross-table PII erasure is an admin-only call.
    @PostMapping("/{id}/erase")
    @PreAuthorize(Authz.ADMINS)
    public ResponseEntity<ApiResponse<Void>> eraseUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody EraseUserRequest request) {
        userService.eraseUserData(callerOrg(principal), id, request.getReason());
        return ResponseEntity.ok(ApiResponse.of("User data erased", null));
    }

    @GetMapping("/{id}/permissions")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<UserPermissionsResponse>> getUserPermissions(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(userService.getUserPermissions(callerOrg(principal), id)));
    }

    @PostMapping("/{id}/permissions/{permissionName}")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<UserPermissionsResponse>> grantPermission(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String permissionName) {
        return ResponseEntity.ok(ApiResponse.success(
                userService.grantDirectPermission(callerOrg(principal), id, permissionName, principal.getId())));
    }

    @DeleteMapping("/{id}/permissions/{permissionName}")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<UserPermissionsResponse>> revokePermission(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String permissionName) {
        return ResponseEntity.ok(ApiResponse.success(
                userService.revokeDirectPermission(callerOrg(principal), id, permissionName)));
    }

    private UUID callerOrg(UserPrincipal principal) {
        boolean isPlatform = principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
        return isPlatform ? null : principal.getOrganizationId();
    }
}
