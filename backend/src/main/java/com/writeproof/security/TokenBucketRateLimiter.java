package com.writeproof.security;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-memory token buckets: each key holds up to {@code capacity} tokens, refilled continuously at
 * {@code capacity / window}. In-process only, so with several instances each enforces its own
 * share; a shared store (e.g. Redis) is the follow-up once there's more than one.
 */
@Component
public class TokenBucketRateLimiter {

    /** @param retryAfter how long until a token is available again (zero when allowed) */
    public record Decision(boolean allowed, Duration retryAfter) {}

    private static final class Bucket {
        double tokens;
        long updatedNanos;
        final double capacity;
        final double perNano;

        Bucket(double capacity, Duration window, long now) {
            this.capacity = capacity;
            this.perNano = capacity / window.toNanos();
            this.tokens = capacity;
            this.updatedNanos = now;
        }

        synchronized Decision take(long now) {
            tokens = Math.min(capacity, tokens + (now - updatedNanos) * perNano);
            updatedNanos = now;
            if (tokens >= 1) {
                tokens -= 1;
                return new Decision(true, Duration.ZERO);
            }
            long wait = (long) Math.ceil((1 - tokens) / perNano);
            return new Decision(false, Duration.ofNanos(wait));
        }

        synchronized boolean idle(long now) {
            return tokens + (now - updatedNanos) * perNano >= capacity;
        }
    }

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    public TokenBucketRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public Decision tryConsume(String key, int capacity, Duration window) {
        long now = nanos();
        return buckets.computeIfAbsent(key, k -> new Bucket(capacity, window, now)).take(now);
    }

    /** Full buckets carry no information; dropping them bounds memory. */
    @Scheduled(fixedDelayString = "PT5M")
    public void evictIdle() {
        long now = nanos();
        buckets.entrySet().removeIf(e -> e.getValue().idle(now));
    }

    /** Forgets every bucket of one subject (an account id or an IP), across all rules. */
    public int forget(String subject) {
        int before = buckets.size();
        buckets.keySet().removeIf(key -> key.endsWith(":" + subject));
        return before - buckets.size();
    }

    int size() {
        return buckets.size();
    }

    /** Forgets all buckets. For tests that need a clean slate between cases. */
    void clear() {
        buckets.clear();
    }

    private long nanos() {
        java.time.Instant i = clock.instant();
        return i.getEpochSecond() * 1_000_000_000L + i.getNano();
    }
}
