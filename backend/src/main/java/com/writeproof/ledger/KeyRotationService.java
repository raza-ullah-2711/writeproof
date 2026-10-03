package com.writeproof.ledger;

import com.writeproof.common.Base64Url;
import com.writeproof.identity.Ed25519;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Rotates the ledger key, and refuses to start with a key nobody vouched for.
 *
 * <p>To rotate, an operator sets {@code LEDGER_PREVIOUS_SIGNING_KEY} to the current key and
 * {@code LEDGER_SIGNING_KEY} to a new one, then restarts. At startup, before the server answers
 * requests, the old key signs a {@link KeyRotation} naming the new key and the ledger's current size
 * and root. Clients and witnesses that pinned the old key follow it instead of refusing the ledger.
 * Once rotated, a restart with the same settings changes nothing.
 */
@Service
public class KeyRotationService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(KeyRotationService.class);

    private final LedgerProperties properties;
    private final LedgerSigner signer;
    private final LedgerService ledger;
    private final CheckpointService checkpoints;
    private final JdbcClient jdbc;
    private final Clock clock;

    KeyRotationService(LedgerProperties properties, LedgerSigner signer, LedgerService ledger,
                       CheckpointService checkpoints, JdbcClient jdbc, Clock clock) {
        this.properties = properties;
        this.signer = signer;
        this.ledger = ledger;
        this.checkpoints = checkpoints;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Every rotation the ledger key went through, oldest first. */
    public List<KeyRotation> rotations() {
        return jdbc.sql("SELECT * FROM ledger_key_rotations ORDER BY id").query(KeyRotationService::map).list();
    }

    @Override
    public void afterPropertiesSet() {
        byte[] current = signer.publicKey();
        List<KeyRotation> rotations = rotations();
        String previousSeed = properties.previousSigningKey();
        if (previousSeed == null || previousSeed.isBlank()) {
            if (!isActive(current, rotations)) {
                throw new IllegalStateException("LEDGER_SIGNING_KEY is not the ledger's key, so every client and "
                        + "witness would refuse the ledger. To rotate the key, set LEDGER_PREVIOUS_SIGNING_KEY to the "
                        + "key in use and LEDGER_SIGNING_KEY to the new one (see docs/ledger.md).");
            }
            return;
        }
        LedgerSigner previous = new LedgerSigner(previousSeed, "LEDGER_PREVIOUS_SIGNING_KEY");
        if (Arrays.equals(previous.publicKey(), current)) {
            throw new IllegalStateException("LEDGER_PREVIOUS_SIGNING_KEY and LEDGER_SIGNING_KEY are the same key");
        }
        if (!rotations.isEmpty() && Arrays.equals(rotations.getLast().newKey(), current)) {
            log.info("Ledger key already rotated to {}; LEDGER_PREVIOUS_SIGNING_KEY can now be removed",
                    Base64Url.encode(current));
            return;
        }
        if (!isActive(previous.publicKey(), rotations)) {
            throw new IllegalStateException("LEDGER_PREVIOUS_SIGNING_KEY is not the ledger's key, so it can't hand "
                    + "the ledger over to LEDGER_SIGNING_KEY");
        }
        if (rotations.stream().anyMatch(r -> Arrays.equals(r.oldKey(), current))) {
            throw new IllegalStateException("LEDGER_SIGNING_KEY was retired by an earlier rotation; use a new key");
        }
        rotate(previous, current);
    }

    /**
     * True if {@code key} is the one clients should currently trust: the last rotation's new key or,
     * before any rotation, the key that signed the published checkpoints (any key, if none are).
     */
    private boolean isActive(byte[] key, List<KeyRotation> rotations) {
        if (!rotations.isEmpty()) {
            return Arrays.equals(rotations.getLast().newKey(), key);
        }
        return checkpoints.latestPublished()
                .map(c -> Ed25519.verify(key, c.signedMessage(), c.signature()))
                .orElse(true);
    }

    private void rotate(LedgerSigner previous, byte[] newKey) {
        long size = ledger.size();
        byte[] root = ledger.root(size);
        long ts = clock.instant().toEpochMilli();
        byte[] oldKey = previous.publicKey();
        KeyRotation rotation = new KeyRotation(oldKey, newKey, size, root, ts,
                previous.sign(KeyRotation.signedMessage(oldKey, newKey, size, root, ts)));
        jdbc.sql("""
                INSERT INTO ledger_key_rotations (old_key, new_key, size, root, timestamp_millis, signature, rotated_at)
                VALUES (:old, :new, :size, :root, :ts, :sig, :at)
                """)
                .param("old", rotation.oldKey())
                .param("new", rotation.newKey())
                .param("size", rotation.size())
                .param("root", rotation.root())
                .param("ts", rotation.timestampMillis())
                .param("sig", rotation.signature())
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
        log.warn("Ledger key rotated from {} to {} at {} entries", Base64Url.encode(oldKey), Base64Url.encode(newKey),
                size);
    }

    private static KeyRotation map(ResultSet rs, int row) throws SQLException {
        return new KeyRotation(rs.getBytes("old_key"), rs.getBytes("new_key"), rs.getLong("size"),
                rs.getBytes("root"), rs.getLong("timestamp_millis"), rs.getBytes("signature"));
    }
}
