package com.writeproof.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    /** A clock the test moves by hand. */
    static final class ManualClock extends Clock {
        Instant now = Instant.parse("2026-10-02T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final ManualClock clock = new ManualClock();
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(clock);
    private static final Duration HOUR = Duration.ofHours(1);

    @Test
    void allowsTheCapacityThenRefusesWithAWait() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume("k", 3, HOUR).allowed()).isTrue();
        }

        TokenBucketRateLimiter.Decision refused = limiter.tryConsume("k", 3, HOUR);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfter()).isEqualTo(Duration.ofMinutes(20)); // one token per 20 min
    }

    @Test
    void refillsContinuously() {
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume("k", 3, HOUR);
        }
        clock.advance(Duration.ofMinutes(19));
        assertThat(limiter.tryConsume("k", 3, HOUR).allowed()).isFalse();

        clock.advance(Duration.ofMinutes(2));
        assertThat(limiter.tryConsume("k", 3, HOUR).allowed()).isTrue();
        assertThat(limiter.tryConsume("k", 3, HOUR).allowed()).isFalse();
    }

    @Test
    void neverAccumulatesBeyondCapacity() {
        clock.advance(Duration.ofDays(30));
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume("k", 3, HOUR).allowed()).isTrue();
        }
        assertThat(limiter.tryConsume("k", 3, HOUR).allowed()).isFalse();
    }

    @Test
    void keysAreIndependent() {
        limiter.tryConsume("alice", 1, HOUR);

        assertThat(limiter.tryConsume("alice", 1, HOUR).allowed()).isFalse();
        assertThat(limiter.tryConsume("bob", 1, HOUR).allowed()).isTrue();
    }

    @Test
    void evictsOnlyBucketsThatHaveFullyRefilled() {
        limiter.tryConsume("old", 2, HOUR);
        clock.advance(Duration.ofMinutes(31)); // "old" is full again
        limiter.tryConsume("recent", 2, HOUR);

        limiter.evictIdle();

        assertThat(limiter.size()).isEqualTo(1);
        // "recent" kept its state: one token left, then refused.
        assertThat(limiter.tryConsume("recent", 2, HOUR).allowed()).isTrue();
        assertThat(limiter.tryConsume("recent", 2, HOUR).allowed()).isFalse();
    }
}
