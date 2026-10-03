package com.writeproof.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin and moderator roles. ADMIN only (SecurityConfig); every change is audited. */
@RestController
@RequestMapping("/api/admin/admins")
class AdminManagementController {

    record GrantRequest(AdminRole role) {}

    private final AdminManagementService admins;

    AdminManagementController(AdminManagementService admins) {
        this.admins = admins;
    }

    @GetMapping
    List<AdminManagementService.Member> members() {
        return admins.members();
    }

    @PutMapping("/{address}")
    ResponseEntity<Void> grant(@AuthenticationPrincipal Jwt jwt, @PathVariable String address,
                               @RequestBody GrantRequest request) {
        if (request.role() == null) {
            throw new IllegalArgumentException("role must be ADMIN or MODERATOR");
        }
        admins.grant(UUID.fromString(jwt.getSubject()), address, request.role());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{address}")
    ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable String address) {
        admins.revoke(UUID.fromString(jwt.getSubject()), address);
        return ResponseEntity.noContent().build();
    }
}
