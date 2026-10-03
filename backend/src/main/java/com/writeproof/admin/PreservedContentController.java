package com.writeproof.admin;

import com.writeproof.common.Base64Url;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Content preserved for law enforcement: ADMIN only (SecurityConfig), every read audited. */
@RestController
@RequestMapping("/api/admin/preserved")
class PreservedContentController {

    record ReportRequest(String reportId) {}

    private final PreservedContent preserved;
    private final AdminRoles roles;

    PreservedContentController(PreservedContent preserved, AdminRoles roles) {
        this.preserved = preserved;
        this.roles = roles;
    }

    @GetMapping
    List<PreservedContent.Summary> list() {
        return preserved.list();
    }

    @GetMapping("/{letterHash}")
    PreservedContent.Copy read(@AuthenticationPrincipal Jwt jwt, @PathVariable String letterHash) {
        UUID actor = UUID.fromString(jwt.getSubject());
        return preserved.read(actor, roles.roleOf(actor).orElseThrow(), hash32(letterHash));
    }

    @PostMapping("/{letterHash}/report")
    PreservedContent.Summary report(@AuthenticationPrincipal Jwt jwt, @PathVariable String letterHash,
                                    @RequestBody ReportRequest request) {
        UUID actor = UUID.fromString(jwt.getSubject());
        return preserved.recordReport(actor, roles.roleOf(actor).orElseThrow(), hash32(letterHash), request.reportId());
    }

    private static byte[] hash32(String value) {
        byte[] hash = Base64Url.decode(value);
        if (hash.length != 32) {
            throw new IllegalArgumentException("A letter hash is 32 bytes");
        }
        return hash;
    }
}
