package com.writeproof.auth;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ChallengeRepository {

    /** What a consumed challenge yields: whose key must have signed it, and the nonce it covered. */
    record ConsumedChallenge(UUID accountId, byte[] publicKey, byte[] nonce) {}

    private final JdbcClient jdbc;

    ChallengeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, UUID accountId, byte[] nonce, Instant createdAt, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO auth_challenges (id, account_id, nonce, created_at, expires_at)
                VALUES (:id, :accountId, :nonce, :createdAt, :expiresAt)
                """)
                .param("id", id)
                .param("accountId", accountId)
                .param("nonce", nonce)
                .param("createdAt", utc(createdAt))
                .param("expiresAt", utc(expiresAt))
                .update();
    }

    /**
     * Atomically marks an unused, unexpired challenge as used and returns it. Each challenge can be
     * consumed at most once, whether or not the signature then verifies, so a nonce can't be retried.
     */
    Optional<ConsumedChallenge> consume(UUID id, Instant now) {
        return jdbc.sql("""
                UPDATE auth_challenges c
                   SET used_at = :now
                  FROM accounts a
                 WHERE c.id = :id
                   AND a.id = c.account_id
                   AND c.used_at IS NULL
                   AND c.expires_at > :now
             RETURNING c.account_id, a.public_key, c.nonce
                """)
                .param("id", id)
                .param("now", utc(now))
                .query((rs, row) -> new ConsumedChallenge(
                        rs.getObject("account_id", UUID.class), rs.getBytes("public_key"), rs.getBytes("nonce")))
                .optional();
    }

    /** Removes challenges that expired before {@code cutoff}; they can never be consumed. */
    int deleteExpiredBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM auth_challenges WHERE expires_at < :cutoff")
                .param("cutoff", utc(cutoff))
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
