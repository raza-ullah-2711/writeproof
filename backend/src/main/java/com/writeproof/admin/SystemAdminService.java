package com.writeproof.admin;

import com.writeproof.handwriting.HandwritingProperties;
import com.writeproof.identity.Ed25519;
import com.writeproof.ledger.Checkpoint;
import com.writeproof.ledger.CheckpointService;
import com.writeproof.ledger.LedgerEntry;
import com.writeproof.ledger.LedgerService;
import com.writeproof.ledger.LedgerSigner;
import com.writeproof.ledger.MerkleTree;
import com.writeproof.security.RateLimitProperties;
import com.writeproof.security.RateLimitRule;
import com.writeproof.system.SystemSettings;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** System controls for admins (Task 13d): switches, ledger tools, and read-only configuration. */
@Service
public class SystemAdminService {

    public record SettingInfo(String key, Instant updatedAt, String updatedBy) {}

    public record Rule(String name, String method, String path, int capacity, long windowSeconds, boolean perAccount) {}

    public record Overview(SystemSettings.Status settings, List<SettingInfo> changes, boolean rateLimitsEnabled,
                           int rateLimitScale, List<Rule> rateLimits, double handwritingThreshold,
                           boolean scoresExposed, long calibrationContributors, long genuineSamples,
                           long forgerySamples, long ledgerSize, Long lastCheckpointSize,
                           Instant lastCheckpointAt) {}

    /** Settings to change; null fields stay as they are. */
    public record Changes(Boolean registrationOpen, Boolean sendingEnabled, Boolean openLettersEnabled,
                          String announcement) {}

    public record CheckpointResult(boolean published, long size) {}

    /** {@code ok} is a component (not a derived method) so it reaches the client in the JSON. */
    public record LedgerAudit(boolean ok, boolean chainIntact, long length, Long brokenAt, int checkpointsChecked,
                              List<Long> rootMismatches, List<Long> badSignatures) {
        static LedgerAudit of(boolean chainIntact, long length, Long brokenAt, int checkpointsChecked,
                              List<Long> rootMismatches, List<Long> badSignatures) {
            return new LedgerAudit(chainIntact && rootMismatches.isEmpty() && badSignatures.isEmpty(), chainIntact,
                    length, brokenAt, checkpointsChecked, rootMismatches, badSignatures);
        }
    }

    static final int AUDIT_CHECKPOINTS = 200;
    private static final int PAGE = 1000;

    private final JdbcClient jdbc;
    private final SystemSettings settings;
    private final AuditLog audit;
    private final LedgerService ledger;
    private final CheckpointService checkpoints;
    private final LedgerSigner signer;
    private final HandwritingProperties handwriting;
    private final RateLimitProperties rateLimits;

    SystemAdminService(JdbcClient jdbc, SystemSettings settings, AuditLog audit, LedgerService ledger,
                       CheckpointService checkpoints, LedgerSigner signer, HandwritingProperties handwriting,
                       RateLimitProperties rateLimits) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.audit = audit;
        this.ledger = ledger;
        this.checkpoints = checkpoints;
        this.signer = signer;
        this.handwriting = handwriting;
        this.rateLimits = rateLimits;
    }

    public Overview overview() {
        List<SettingInfo> changes = jdbc.sql("""
                SELECT key, updated_at, updated_by FROM system_settings ORDER BY key
                """)
                .query((rs, row) -> new SettingInfo(rs.getString("key"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                        rs.getBytes("updated_by") == null ? null
                                : com.writeproof.common.Base64Url.encode(rs.getBytes("updated_by"))))
                .list();
        List<Rule> rules = RateLimitRule.DEFAULTS.stream()
                .map(r -> new Rule(r.name(), r.method().name(), r.pathPattern(), r.capacity() * rateLimits.scale(),
                        r.window().toSeconds(), r.perAccount()))
                .toList();
        Map<String, Long> counts = jdbc.sql("""
                SELECT (SELECT count(*) FROM calibration_contributors) AS contributors,
                       (SELECT count(*) FROM calibration_samples WHERE kind = 'genuine') AS genuine,
                       (SELECT count(*) FROM calibration_samples WHERE kind = 'forgery') AS forgery
                """)
                .query((rs, row) -> Map.of("contributors", rs.getLong(1), "genuine", rs.getLong(2),
                        "forgery", rs.getLong(3)))
                .single();
        var last = checkpoints.latestPublished();
        // No checkpoint yet on a fresh server: absent, not an error.
        Instant lastAt = jdbc.sql("SELECT published_at FROM ledger_checkpoints ORDER BY size DESC LIMIT 1")
                .query((rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .optional()
                .orElse(null);
        return new Overview(settings.status(), changes, rateLimits.enabled(), rateLimits.scale(), rules,
                handwriting.threshold(), handwriting.exposeScores(), counts.get("contributors"),
                counts.get("genuine"), counts.get("forgery"), ledger.size(),
                last.map(Checkpoint::size).orElse(null), lastAt);
    }

    /** Applies the given changes; each changed setting is audited with its old and new value. */
    @Transactional
    public SystemSettings.Status change(UUID actor, Changes c) {
        flag(actor, "registration_open", c.registrationOpen());
        flag(actor, "sending_enabled", c.sendingEnabled());
        flag(actor, "open_letters_enabled", c.openLettersEnabled());
        if (c.announcement() != null) {
            String before = settings.setAnnouncement(c.announcement(), actor);
            String after = c.announcement().trim();
            if (!before.equals(after)) {
                audit.record(actor, AdminRole.ADMIN, "system.setting-changed", "announcement",
                        Map.of("from", before, "to", after));
            }
        }
        return settings.status();
    }

    private void flag(UUID actor, String key, Boolean on) {
        if (on == null) {
            return;
        }
        boolean before = settings.setFlag(key, on, actor);
        if (before != on) {
            audit.record(actor, AdminRole.ADMIN, "system.setting-changed", key, Map.of("from", before, "to", on));
        }
    }

    /** Publishes a checkpoint now, if the ledger grew since the last one. */
    @Transactional
    public CheckpointResult publishCheckpoint(UUID actor) {
        var published = checkpoints.publishIfGrown();
        published.ifPresent(c -> audit.record(actor, AdminRole.ADMIN, "ledger.checkpoint-published", null,
                Map.of("size", c.size())));
        return new CheckpointResult(published.isPresent(),
                published.map(Checkpoint::size).orElseGet(() -> checkpoints.latestPublished()
                        .map(Checkpoint::size).orElse(0L)));
    }

    /**
     * Walks the hash chain from genesis, then checks the latest published checkpoints: each must be
     * signed by the ledger key and match the Merkle root of the ledger as it is now. A mismatch
     * means history was rewritten after that checkpoint was published.
     */
    @Transactional
    public LedgerAudit auditLedger(UUID actor) {
        LedgerService.ChainCheck chain = ledger.verify();
        List<byte[]> leaves = new ArrayList<>();
        for (long from = 1; ; from += PAGE) {
            List<LedgerEntry> page = ledger.entries(from, PAGE);
            page.forEach(e -> leaves.add(MerkleTree.leafHash(e.entryHash())));
            if (page.size() < PAGE) {
                break;
            }
        }
        List<Checkpoint> published = jdbc.sql("""
                SELECT size, root, timestamp_millis, signature FROM ledger_checkpoints
                 ORDER BY size DESC LIMIT :limit
                """)
                .param("limit", AUDIT_CHECKPOINTS)
                .query((rs, row) -> new Checkpoint(rs.getLong("size"), rs.getBytes("root"),
                        rs.getLong("timestamp_millis"), rs.getBytes("signature")))
                .list();
        byte[] key = signer.publicKey();
        List<Long> mismatches = new ArrayList<>();
        List<Long> badSignatures = new ArrayList<>();
        for (Checkpoint c : published) {
            if (!Ed25519.verify(key, c.signedMessage(), c.signature())) {
                badSignatures.add(c.size());
            }
            if (c.size() > leaves.size()
                    || !Arrays.equals(MerkleTree.root(leaves.subList(0, (int) c.size())), c.root())) {
                mismatches.add(c.size());
            }
        }
        LedgerAudit result = LedgerAudit.of(chain.intact(), chain.length(), chain.brokenAt(), published.size(),
                mismatches, badSignatures);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ok", result.ok());
        detail.put("length", result.length());
        detail.put("checkpointsChecked", result.checkpointsChecked());
        if (!result.ok()) {
            detail.put("brokenAt", result.brokenAt());
            detail.put("rootMismatches", mismatches);
            detail.put("badSignatures", badSignatures);
        }
        audit.record(actor, AdminRole.ADMIN, "ledger.audited", null, detail);
        return result;
    }
}
