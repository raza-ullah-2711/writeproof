package com.writeproof.admin;

import com.writeproof.common.Base64Url;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Who is an admin. Looked up on every request (not baked into the 15-minute token), so a revoked
 * role stops working at once.
 */
@Service
public class AdminRoles {

    private final JdbcClient jdbc;
    private final List<byte[]> bootstrap;

    AdminRoles(JdbcClient jdbc, AdminProperties properties) {
        this.jdbc = jdbc;
        this.bootstrap = properties.bootstrapKeys().stream().map(AdminRoles::key).toList();
    }

    /** True if this key is listed in ADMIN_PUBLIC_KEYS (always ADMIN; managed in configuration). */
    public boolean isBootstrap(byte[] publicKey) {
        return bootstrap.stream().anyMatch(k -> Arrays.equals(k, publicKey));
    }

    public List<byte[]> bootstrapKeys() {
        return bootstrap.stream().map(byte[]::clone).toList();
    }

    public Optional<AdminRole> roleOf(UUID accountId) {
        return jdbc.sql("""
                SELECT a.public_key, r.role
                  FROM accounts a LEFT JOIN admin_roles r ON r.public_key = a.public_key
                 WHERE a.id = :id
                """)
                .param("id", accountId)
                .query((rs, row) -> {
                    byte[] publicKey = rs.getBytes("public_key");
                    if (bootstrap.stream().anyMatch(k -> Arrays.equals(k, publicKey))) {
                        return Optional.of(AdminRole.ADMIN);
                    }
                    String role = rs.getString("role");
                    return role == null ? Optional.<AdminRole>empty() : Optional.of(AdminRole.valueOf(role));
                })
                .optional()
                .flatMap(r -> r);
    }

    private static byte[] key(String value) {
        byte[] key = Base64Url.decode(value);
        if (key.length != 32) {
            throw new IllegalStateException("ADMIN_PUBLIC_KEYS must list 32-byte wallet addresses (base64url)");
        }
        return key;
    }
}
