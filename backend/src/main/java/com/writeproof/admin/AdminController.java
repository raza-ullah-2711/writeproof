package com.writeproof.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The admin area's API. Access rules are in SecurityConfig: everything here except /me needs ADMIN. */
@RestController
@RequestMapping("/api/admin")
class AdminController {

    /** {@code role} is null for an ordinary account. */
    record Me(AdminRole role) {}

    private final AdminRoles roles;
    private final DashboardService dashboard;
    private final AuditLog audit;

    AdminController(AdminRoles roles, DashboardService dashboard, AuditLog audit) {
        this.roles = roles;
        this.dashboard = dashboard;
        this.audit = audit;
    }

    @GetMapping("/me")
    Me me(@AuthenticationPrincipal Jwt jwt) {
        return new Me(roles.roleOf(UUID.fromString(jwt.getSubject())).orElse(null));
    }

    @GetMapping("/dashboard")
    DashboardService.Dashboard dashboard() {
        return dashboard.dashboard();
    }

    @GetMapping("/audit")
    List<AuditLog.Entry> audit(@RequestParam(required = false) Long before,
                               @RequestParam(defaultValue = "50") int limit) {
        return audit.list(before, Math.clamp(limit, 1, 200));
    }
}
