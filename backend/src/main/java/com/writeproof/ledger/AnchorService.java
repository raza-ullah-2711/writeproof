package com.writeproof.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.identity.Ed25519;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Anchors every published checkpoint in a public transparency log (Rekor), so that all the
 * checkpoints the ledger key ever signed sit in one append-only log the operator doesn't run.
 * Showing someone a different history would mean anchoring it there too, where witnesses find it.
 * Runs on a schedule, after the checkpoint is stored, and simply retries what failed.
 */
@Service
public class AnchorService {

    /** An anchored checkpoint: where its entry is in the log. */
    public record Anchor(long size, String logUrl, long logIndex, String uuid, long integratedTime) {}

    private static final Logger log = LoggerFactory.getLogger(AnchorService.class);
    private static final int BATCH = 20;

    private final AnchorProperties properties;
    private final LedgerSigner signer;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final Rekor rekor;

    AnchorService(AnchorProperties properties, LedgerSigner signer, JdbcClient jdbc, Clock clock, ObjectMapper json) {
        this.properties = properties;
        this.signer = signer;
        this.jdbc = jdbc;
        this.clock = clock;
        this.rekor = properties.enabled()
                ? new Rekor(HttpClient.newHttpClient(), URI.create(properties.rekorUrl()), json)
                : null;
    }

    public boolean enabled() {
        return rekor != null;
    }

    /** Anchors published checkpoints that aren't yet, oldest first; returns how many it anchored. */
    @Scheduled(fixedDelayString = "${writeproof.ledger.anchor.interval:PT5M}",
               initialDelayString = "${writeproof.ledger.anchor.interval:PT5M}")
    public int anchorPending() {
        if (rekor == null) {
            return 0;
        }
        List<Checkpoint> pending = jdbc.sql("""
                SELECT c.* FROM ledger_checkpoints c
                 WHERE NOT EXISTS (SELECT 1 FROM ledger_anchors a WHERE a.size = c.size)
                 ORDER BY c.size LIMIT :limit
                """).param("limit", BATCH).query(AnchorService::checkpoint).list();
        int anchored = 0;
        byte[] key = signer.publicKey();
        for (Checkpoint c : pending) {
            if (!Ed25519.verify(key, c.signedMessage(), c.signature())) {
                // Signed by a key retired before it was anchored: the current key can't vouch for it.
                log.warn("Not anchoring the checkpoint at size {}: it was signed by a retired ledger key", c.size());
                continue;
            }
            byte[] payload = c.signedMessage();
            try {
                Rekor.Entry entry = rekor.submit(payload, signer.sign(Rekor.pae(Rekor.PAYLOAD_TYPE, payload)), key);
                jdbc.sql("""
                        INSERT INTO ledger_anchors (size, log_url, log_index, entry_uuid, integrated_time, anchored_at)
                        VALUES (:size, :url, :index, :uuid, :time, :at)
                        ON CONFLICT (size) DO NOTHING
                        """)
                        .param("size", c.size()).param("url", rekor.url()).param("index", entry.logIndex())
                        .param("uuid", entry.uuid()).param("time", entry.integratedTime())
                        .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                        .update();
                anchored++;
            } catch (IOException e) {
                log.warn("Couldn't anchor the checkpoint at size {} in {} (will retry): {}", c.size(), rekor.url(),
                        e.getMessage());
                break;
            }
        }
        return anchored;
    }

    /** The latest anchor, its checkpoint, and its entry as the log returns it: for browsers to check. */
    public record AnchorProof(Anchor anchor, Checkpoint checkpoint, Rekor.Entry entry) {}

    /**
     * The latest anchor with what a browser needs to verify it against the log's key (browsers can
     * only reach this origin, so the server relays the log's answer; it can't forge it). Empty if
     * nothing is anchored yet.
     */
    public Optional<AnchorProof> latestProof() throws IOException {
        Optional<Anchor> latest = jdbc.sql("SELECT * FROM ledger_anchors ORDER BY size DESC LIMIT 1")
                .query(AnchorService::anchor).optional();
        if (latest.isEmpty() || rekor == null || !latest.get().logUrl().equals(rekor.url())) {
            return Optional.empty();
        }
        Checkpoint checkpoint = jdbc.sql("SELECT * FROM ledger_checkpoints WHERE size = :size")
                .param("size", latest.get().size()).query(AnchorService::checkpoint).single();
        return Optional.of(new AnchorProof(latest.get(), checkpoint, rekor.entry(latest.get().uuid())));
    }

    public List<Anchor> anchors(long afterSize, int limit) {
        return jdbc.sql("SELECT * FROM ledger_anchors WHERE size > :after ORDER BY size LIMIT :limit")
                .param("after", afterSize).param("limit", limit)
                .query(AnchorService::anchor).list();
    }

    private static Anchor anchor(ResultSet rs, int row) throws SQLException {
        return new Anchor(rs.getLong("size"), rs.getString("log_url"), rs.getLong("log_index"),
                rs.getString("entry_uuid"), rs.getLong("integrated_time"));
    }

    private static Checkpoint checkpoint(ResultSet rs, int row) throws SQLException {
        return new Checkpoint(rs.getLong("size"), rs.getBytes("root"), rs.getLong("timestamp_millis"),
                rs.getBytes("signature"));
    }
}
