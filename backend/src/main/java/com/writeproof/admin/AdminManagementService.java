package com.writeproof.admin;

import com.writeproof.common.Base64Url;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who administers Writeproof (Task 13e). Roles are granted by wallet address and take effect on
 * the next request. Bootstrap admins (ADMIN_PUBLIC_KEYS) are listed but managed in configuration,
 * and nobody can change their own role, so the admin team can't lock itself out.
 */
@Service
public class AdminManagementService {

    /** {@code source} is "configuration" for bootstrap admins, otherwise "granted". */
    public record Member(String publicKey, AdminRole role, String source, Instant grantedAt, String grantedBy,
                         boolean hasAccount, UUID accountId) {}

    private final JdbcClient jdbc;
    private final AdminRoles roles;
    private final AuditLog audit;
    private final Clock clock;

    AdminManagementService(JdbcClient jdbc, AdminRoles roles, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.roles = roles;
        this.audit = audit;
        this.clock = clock;
    }

    public List<Member> members() {
        List<Member> members = new ArrayList<>();
        for (byte[] key : roles.bootstrapKeys()) {
            Optional<UUID> account = accountOf(key);
            members.add(new Member(Base64Url.encode(key), AdminRole.ADMIN, "configuration", null, null,
                    account.isPresent(), account.orElse(null)));
        }
        jdbc.sql("""
                SELECT r.public_key, r.role, r.granted_at, r.granted_by, a.id AS account_id
                  FROM admin_roles r LEFT JOIN accounts a ON a.public_key = r.public_key
                 ORDER BY r.role, r.granted_at
                """)
                .query((rs, row) -> {
                    byte[] key = rs.getBytes("public_key");
                    if (roles.isBootstrap(key)) {
                        return null; // configuration wins; listed above
                    }
                    byte[] by = rs.getBytes("granted_by");
                    UUID account = rs.getObject("account_id", UUID.class);
                    return new Member(Base64Url.encode(key), AdminRole.valueOf(rs.getString("role")), "granted",
                            rs.getObject("granted_at", OffsetDateTime.class).toInstant(),
                            by == null ? null : Base64Url.encode(by), account != null, account);
                })
                .list()
                .stream()
                .filter(m -> m != null)
                .forEach(members::add);
        return members;
    }

    /** Grants {@code role} to the address, or changes its role. */
    @Transactional
    public void grant(UUID actor, String address, AdminRole role) {
        byte[] key = key(address);
        guard(actor, key);
        Optional<UUID> account = accountOf(key);
        if (account.isPresent() && suspended(account.get())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reinstate this account before giving it a role");
        }
        Optional<AdminRole> previous = current(key);
        if (previous.equals(Optional.of(role))) {
            return; // nothing to change, nothing to audit
        }
        jdbc.sql("""
                INSERT INTO admin_roles (public_key, role, granted_at, granted_by)
                SELECT :key, :role, :at, a.public_key FROM accounts a WHERE a.id = :actor
                ON CONFLICT (public_key) DO UPDATE
                   SET role = EXCLUDED.role, granted_at = EXCLUDED.granted_at, granted_by = EXCLUDED.granted_by
                """)
                .param("key", key).param("role", role.name())
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).param("actor", actor)
                .update();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("role", role.name());
        previous.ifPresent(p -> detail.put("previous", p.name()));
        detail.put("hasAccount", account.isPresent());
        audit.record(actor, AdminRole.ADMIN, previous.isPresent() ? "admin.role-changed" : "admin.role-granted",
                address, detail);
    }

    @Transactional
    public void revoke(UUID actor, String address) {
        byte[] key = key(address);
        guard(actor, key);
        AdminRole previous = current(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This address has no role"));
        jdbc.sql("DELETE FROM admin_roles WHERE public_key = :key").param("key", key).update();
        audit.record(actor, AdminRole.ADMIN, "admin.role-revoked", address, Map.of("role", previous.name()));
    }

    private void guard(UUID actor, byte[] key) {
        if (roles.isBootstrap(key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This admin is set in ADMIN_PUBLIC_KEYS; change it in the server configuration");
        }
        byte[] own = jdbc.sql("SELECT public_key FROM accounts WHERE id = :id").param("id", actor)
                .query(byte[].class).single();
        if (Arrays.equals(own, key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You can't change your own role; ask another admin");
        }
    }

    private Optional<AdminRole> current(byte[] key) {
        return jdbc.sql("SELECT role FROM admin_roles WHERE public_key = :key").param("key", key)
                .query(String.class).optional().map(AdminRole::valueOf);
    }

    private Optional<UUID> accountOf(byte[] key) {
        return jdbc.sql("SELECT id FROM accounts WHERE public_key = :key").param("key", key).query(UUID.class)
                .optional();
    }

    private boolean suspended(UUID accountId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM account_status WHERE account_id = :id "
                        + "AND suspended_at IS NOT NULL)")
                .param("id", accountId).query(Boolean.class).single();
    }

    private static byte[] key(String address) {
        byte[] key;
        try {
            key = Base64Url.decode(address == null ? "" : address.trim());
        } catch (IllegalArgumentException e) {
            key = new byte[0];
        }
        if (key.length != 32) {
            throw new IllegalArgumentException("That is not a Writeproof address");
        }
        return key;
    }
}
