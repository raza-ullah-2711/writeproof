package com.writeproof.admin;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param bootstrapKeys wallet addresses (base64url public keys) that are always ADMIN while listed,
 *                      from {@code ADMIN_PUBLIC_KEYS} (comma-separated). How the first admin gets in.
 */
@ConfigurationProperties("writeproof.admin")
public record AdminProperties(List<String> bootstrapKeys) {

    public AdminProperties {
        bootstrapKeys = bootstrapKeys == null ? List.of()
                : bootstrapKeys.stream().map(String::trim).filter(k -> !k.isEmpty()).toList();
    }
}
