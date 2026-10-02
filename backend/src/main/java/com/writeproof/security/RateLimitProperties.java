package com.writeproof.security;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param enabled whether limits are enforced at all
 * @param scale   multiplies every limit; 1 in production, larger where many requests come from
 *                one place on purpose (e.g. integration tests on localhost)
 */
@Validated
@ConfigurationProperties("writeproof.rate-limits")
public record RateLimitProperties(boolean enabled, @Positive int scale) {}
