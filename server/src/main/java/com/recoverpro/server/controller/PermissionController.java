package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.dto.response.PermissionResponse;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final RoleService roleService;

    /** GET /api/v1/permissions -- returns permissions the caller is allowed to assign */
    @GetMapping
    @PreAuthorize(Authz.ADMINS_OR_ROLE_ASSIGN)
    public ResponseEntity<ApiResponse<List<PermissionResponse>>> getAllPermissions() {
        return ResponseEntity.ok(ApiResponse.success(roleService.listPermissionsForCaller()));
    }
}