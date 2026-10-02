package com.writeproof.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param jwtSecret     base64-encoded HMAC key (at least 256 bits), from {@code JWT_SECRET}
 * @param tokenTtl      lifetime of issued access tokens
 * @param challengeTtl  how long a login nonce may be answered
 */
@Validated
@ConfigurationProperties("writeproof.auth")
public record AuthProperties(@NotBlank String jwtSecret, @NotNull Duration tokenTtl, @NotNull Duration challengeTtl) {}
