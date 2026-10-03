package com.writeproof.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Account management. ADMIN only (SecurityConfig); every action is audited. */
@RestController
@RequestMapping("/api/admin/accounts")
class AccountAdminController {

    record SuspendRequest(String reason) {}

    record SignedOut(Instant tokensBefore) {}

    record RateLimitsReset(int buckets) {}

    private final AccountAdminService accounts;

    AccountAdminController(AccountAdminService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    List<AccountAdminService.Summary> search(@RequestParam(required = false) String query,
                                             @RequestParam(defaultValue = "50") int limit) {
        return accounts.search(query, Math.clamp(limit, 1, 200));
    }

    @GetMapping("/{id}")
    AccountAdminService.Detail detail(@PathVariable UUID id) {
        return accounts.detail(id);
    }

    @PostMapping("/{id}/suspension")
    ResponseEntity<Void> suspend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                 @RequestBody SuspendRequest request) {
        accounts.suspend(actor(jwt), AdminRole.ADMIN, id, request.reason());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/suspension")
    ResponseEntity<Void> reinstate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        accounts.reinstate(actor(jwt), AdminRole.ADMIN, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/sign-out")
    SignedOut signOut(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return new SignedOut(accounts.signOut(actor(jwt), AdminRole.ADMIN, id));
    }

    @PostMapping("/{id}/rate-limits/reset")
    RateLimitsReset resetRateLimits(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return new RateLimitsReset(accounts.resetRateLimits(actor(jwt), AdminRole.ADMIN, id));
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
