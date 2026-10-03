package com.writeproof.admin;

import com.writeproof.common.Base64Url;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Moderation of open letters: ADMIN or MODERATOR (SecurityConfig). Every decision is audited. */
@RestController
@RequestMapping("/api/admin/moderation")
class ModerationController {

    record DismissRequest(String note) {}

    record RemoveRequest(String category, String note) {}

    private final ModerationService moderation;
    private final AdminRoles roles;

    ModerationController(ModerationService moderation, AdminRoles roles) {
        this.moderation = moderation;
        this.roles = roles;
    }

    @GetMapping("/queue")
    List<ModerationService.Case> queue(@RequestParam(defaultValue = "50") int limit) {
        return moderation.queue(Math.clamp(limit, 1, 200));
    }

    @GetMapping("/letters/{letterHash}")
    ModerationService.Case letter(@PathVariable String letterHash) {
        return moderation.letter(hash32(letterHash));
    }

    @PostMapping("/letters/{letterHash}/dismiss")
    Map<String, Integer> dismiss(@AuthenticationPrincipal Jwt jwt, @PathVariable String letterHash,
                                 @RequestBody(required = false) DismissRequest request) {
        UUID actor = UUID.fromString(jwt.getSubject());
        return Map.of("reportsResolved", moderation.dismiss(actor, role(actor), hash32(letterHash),
                request == null ? null : request.note()));
    }

    @PostMapping("/letters/{letterHash}/remove")
    ModerationService.Case remove(@AuthenticationPrincipal Jwt jwt, @PathVariable String letterHash,
                                  @RequestBody RemoveRequest request) {
        UUID actor = UUID.fromString(jwt.getSubject());
        byte[] hash = hash32(letterHash);
        moderation.remove(actor, role(actor), hash, request.category(), request.note());
        return moderation.letter(hash);
    }

    /** The actor's actual role, so the audit log tells moderators' decisions from admins'. */
    private AdminRole role(UUID actor) {
        return roles.roleOf(actor).orElseThrow();
    }

    private static byte[] hash32(String value) {
        byte[] hash = Base64Url.decode(value);
        if (hash.length != 32) {
            throw new IllegalArgumentException("A letter hash is 32 bytes");
        }
        return hash;
    }
}
