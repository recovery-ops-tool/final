package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.dto.request.CreateRoleRequest;
import com.recoverpro.server.dto.response.PermissionResponse;
import com.recoverpro.server.dto.response.RoleResponse;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.PlatformAdminAccessGuard;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
public class RoleController {

    private static final String ADMIN_ROLES = Authz.LEADS;
    // A custom role granted ROLE_ASSIGN via Role Management can edit role permissions without
    // needing ORG_ADMIN/PLATFORM_ADMIN — see UserController's matching CAN_CREATE_USER.
    private static final String CAN_ASSIGN_ROLE = ADMIN_ROLES + " or hasAuthority('ROLE_ASSIGN')";

    private final RoleService roleService;
    private final PlatformAdminAccessGuard platformAdminAccessGuard;

    @GetMapping
    @PreAuthorize(CAN_ASSIGN_ROLE)
    public ResponseEntity<ApiResponse<List<RoleResponse>>> listRoles(
            @AuthenticationPrincipal UserPrincipal principal) {
        elevateIfPlatformAdmin(principal, "roles:list");
        return ResponseEntity.ok(ApiResponse.success(roleService.listRolesForCaller()));
    }

    @GetMapping("/{id}")
    @PreAuthorize(CAN_ASSIGN_ROLE)
    public ResponseEntity<ApiResponse<RoleResponse>> getRole(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {
        elevateIfPlatformAdmin(principal, "roles:getById:" + id);
        return ResponseEntity.ok(ApiResponse.success(roleService.getRoleById(id)));
    }

    @PostMapping
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<RoleResponse>> createRole(
            @Valid @RequestBody CreateRoleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(roleService.createRole(request)));
    }

    // SYSTEM 07 TASK 7.4: deliberately NOT converted to a typed DTO -- Set<UUID> is already a
    // complete constraint (Jackson rejects a malformed UUID before this method ever runs, via
    // the existing HttpMessageNotReadableException handler), there's no free-text/format gap a
    // DTO wrapper would meaningfully close. Empty set is valid input (removes all permissions).
    @PatchMapping("/{id}/permissions")
    @PreAuthorize(CAN_ASSIGN_ROLE)
    public ResponseEntity<ApiResponse<RoleResponse>> updatePermissions(
            @PathVariable UUID id,
            @RequestBody Set<UUID> permissionIds,
            @AuthenticationPrincipal UserPrincipal principal) {
        elevateIfPlatformAdmin(principal, "roles:updatePermissions:" + id);
        return ResponseEntity.ok(ApiResponse.success(roleService.updateRolePermissions(id, permissionIds)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<Void>> deleteRole(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {
        elevateIfPlatformAdmin(principal, "roles:delete:" + id);
        roleService.deleteRole(id);
        return ResponseEntity.ok(ApiResponse.of("Role deleted", null));
    }

    @GetMapping("/permissions")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<List<PermissionResponse>>> listPermissions() {
        return ResponseEntity.ok(ApiResponse.success(roleService.listPermissionsForCaller()));
    }

    /**
     * roles' RLS policy (V067) only escapes for a platform admin once app.is_platform_admin is set --
     * RoleServiceImpl's own isPlatformAdmin branches (listRolesForCaller's findAll(),
     * assertCanAccessRole/assertCanModifyRole's early return) were already correct but dead for
     * org-specific roles, because roleRepository.findById/findAll already filtered those out via RLS
     * before that logic ran. createRole/listPermissions don't fetch an existing org-scoped row by id
     * (createRole only ever writes organization_id=NULL for a platform admin; listPermissions reads
     * the non-RLS'd permissions catalog), so neither needs elevation.
     */
    private void elevateIfPlatformAdmin(UserPrincipal principal, String resource) {
        boolean isPlatformAdmin = principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
        if (isPlatformAdmin) {
            platformAdminAccessGuard.beginUnattendedCrossOrgAccess(principal.getId(), resource);
        }
    }
}
