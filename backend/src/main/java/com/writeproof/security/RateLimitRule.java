package com.writeproof.security;

import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * One limit: at most {@code capacity} requests per {@code window} for a method + path pattern,
 * counted per account (when the endpoint needs a login) or per client IP (when it doesn't).
 */
public record RateLimitRule(String name, HttpMethod method, String pathPattern, int capacity, Duration window,
                     boolean perAccount) {

    /** The defaults. Tight on what's expensive or probes secrets, loose on ordinary use. */
    public static final List<RateLimitRule> DEFAULTS = List.of(
            new RateLimitRule("register", HttpMethod.POST, "/api/accounts", 10, Duration.ofHours(1), false),
            new RateLimitRule("login-challenge", HttpMethod.POST, "/api/auth/challenge", 30, Duration.ofMinutes(10), false),
            new RateLimitRule("login-verify", HttpMethod.POST, "/api/auth/verify", 30, Duration.ofMinutes(10), false),
            // Each attempt reveals whether a sample matched; keep forgery-by-trial expensive.
            new RateLimitRule("handwriting-verify", HttpMethod.POST, "/api/handwriting/verify", 20, Duration.ofHours(1), true),
            new RateLimitRule("handwriting-enrol", HttpMethod.POST, "/api/handwriting/enrolment", 10, Duration.ofHours(1), true),
            new RateLimitRule("handwriting-delete", HttpMethod.POST, "/api/handwriting/enrolment/deletion", 5, Duration.ofHours(1), true),
            new RateLimitRule("send-letter", HttpMethod.POST, "/api/letters", 30, Duration.ofHours(1), true),
            new RateLimitRule("backup-fetch", HttpMethod.GET, "/api/backups/*", 20, Duration.ofHours(1), false),
            new RateLimitRule("backup-upload", HttpMethod.PUT, "/api/me/backup", 10, Duration.ofHours(1), true));
}
