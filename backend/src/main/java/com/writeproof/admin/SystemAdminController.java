package com.writeproof.admin;

import com.writeproof.system.SystemSettings;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** System controls. ADMIN only (SecurityConfig); every change is audited. */
@RestController
@RequestMapping("/api/admin/system")
class SystemAdminController {

    private final SystemAdminService system;

    SystemAdminController(SystemAdminService system) {
        this.system = system;
    }

    @GetMapping
    SystemAdminService.Overview overview() {
        return system.overview();
    }

    @PatchMapping("/settings")
    SystemSettings.Status change(@AuthenticationPrincipal Jwt jwt, @RequestBody SystemAdminService.Changes changes) {
        return system.change(actor(jwt), changes);
    }

    @PostMapping("/ledger/checkpoint")
    SystemAdminService.CheckpointResult checkpoint(@AuthenticationPrincipal Jwt jwt) {
        return system.publishCheckpoint(actor(jwt));
    }

    @PostMapping("/ledger/audit")
    SystemAdminService.LedgerAudit audit(@AuthenticationPrincipal Jwt jwt) {
        return system.auditLedger(actor(jwt));
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
