package com.writeproof.handwriting;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Rotates {@code HANDWRITING_DATA_KEY}: an operator sets the new key, lists the old one in
 * {@code HANDWRITING_PREVIOUS_DATA_KEYS} and restarts. Everything stays readable, and this job
 * re-encrypts the stored rows under the new key in the background. Once it logs that nothing is
 * left under a previous key, the old key can be removed. At startup it refuses to run with keys
 * that can't read the stored data, rather than failing on every enrolment later.
 */
@Service
public class BiometricKeyRotation implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(BiometricKeyRotation.class);
    private static final int BATCH = 200;

    /** An encrypted column: its table, row key, owner (part of the associated data) and context. */
    private record Column(String table, String id, String owner, String data, Function<UUID, String> context) {}

    private static final List<Column> COLUMNS = List.of(
            new Column("handwriting_enrolments", "account_id", "account_id", "samples_encrypted",
                    BiometricCipher::enrolmentContext),
            new Column("handwriting_history", "id", "account_id", "sample_encrypted", BiometricCipher::historyContext),
            new Column("calibration_samples", "id", "contributor_id", "sample_encrypted",
                    BiometricCipher::calibrationContext),
            new Column("preserved_content", "letter_hash", "author_id", "body_encrypted",
                    BiometricCipher::preservationContext));

    private final BiometricCipher cipher;
    private final JdbcClient jdbc;
    private final boolean rotating;

    BiometricKeyRotation(BiometricCipher cipher, JdbcClient jdbc, HandwritingProperties properties) {
        this.cipher = cipher;
        this.jdbc = jdbc;
        this.rotating = properties.previousDataKeys() != null && !properties.previousDataKeys().isBlank();
    }

    /** A row not yet under the current key must open with one of the configured keys. */
    @Override
    public void afterPropertiesSet() {
        for (Column c : COLUMNS) {
            jdbc.sql("SELECT " + c.owner() + ", " + c.data() + " FROM " + c.table() + " WHERE " + notCurrent(c)
                            + " LIMIT 1")
                    .param("key", cipher.currentKeyId())
                    .query((rs, row) -> {
                        try {
                            cipher.decrypt(rs.getBytes(2), c.context().apply(rs.getObject(1, UUID.class)));
                        } catch (IllegalStateException e) {
                            throw new IllegalStateException("HANDWRITING_DATA_KEY can't decrypt the stored handwriting ("
                                    + c.table() + "). If you changed it, list the old key in "
                                    + "HANDWRITING_PREVIOUS_DATA_KEYS (see docs/security.md).", e);
                        }
                        return null;
                    })
                    .list();
        }
    }

    /** Rows not yet under the current key: re-encrypted by {@link #reencryptPending}. */
    public long pending() {
        long n = 0;
        for (Column c : COLUMNS) {
            n += jdbc.sql("SELECT count(*) FROM " + c.table() + " WHERE " + notCurrent(c))
                    .param("key", cipher.currentKeyId()).query(Long.class).single();
        }
        return n;
    }

    /** Re-encrypts every stored row that isn't under the current key; returns how many it rewrote. */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${writeproof.handwriting.rotation-interval:PT10M}")
    public int reencryptPending() {
        int rewritten = 0;
        int unreadable = 0;
        for (Column c : COLUMNS) {
            Object after = null;
            while (true) {
                List<Object[]> rows = jdbc.sql("SELECT " + c.id() + ", " + c.owner() + ", " + c.data() + " FROM "
                                + c.table() + " WHERE " + notCurrent(c) + (after == null ? "" : " AND " + c.id()
                                + " > :after") + " ORDER BY " + c.id() + " LIMIT " + BATCH)
                        .param("key", cipher.currentKeyId())
                        .params(after == null ? Map.of() : Map.of("after", after))
                        .query((rs, row) -> new Object[] {rs.getObject(1), rs.getObject(2, UUID.class), rs.getBytes(3)})
                        .list();
                for (Object[] r : rows) {
                    String context = c.context().apply((UUID) r[1]);
                    byte[] old = (byte[]) r[2];
                    String plaintext;
                    try {
                        plaintext = cipher.decrypt(old, context);
                    } catch (IllegalStateException e) {
                        unreadable++;
                        continue;
                    }
                    // Only if unchanged since read: a concurrent rewrite (say, a new enrolment) wins.
                    rewritten += jdbc.sql("UPDATE " + c.table() + " SET " + c.data() + " = :data WHERE " + c.id()
                                    + " = :id AND " + c.data() + " = :old")
                            .param("data", cipher.encrypt(plaintext, context))
                            .param("id", r[0]).param("old", old).update();
                }
                if (rows.size() < BATCH) {
                    break;
                }
                after = rows.getLast()[0];
            }
        }
        if (rewritten > 0) {
            log.info("Re-encrypted {} stored handwriting values under the current HANDWRITING_DATA_KEY", rewritten);
        }
        if (unreadable > 0) {
            log.error("{} stored handwriting values open with none of the configured keys; they were left as they are",
                    unreadable);
        } else if (rotating && pending() == 0) {
            log.info("No handwriting data is left under a previous key: HANDWRITING_PREVIOUS_DATA_KEYS can be removed");
        }
        return rewritten;
    }

    private static String notCurrent(Column c) {
        return "NOT (get_byte(" + c.data() + ", 0) = 2 AND substring(" + c.data() + " from 2 for 4) = :key)";
    }
}
