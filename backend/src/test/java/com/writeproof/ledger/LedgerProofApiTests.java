package com.writeproof.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.TestcontainersConfiguration;
import com.writeproof.common.Base64Url;
import com.writeproof.identity.Ed25519;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerProofApiTests {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private CheckpointService checkpoints;

    @Autowired
    private LedgerSigner signer;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcClient jdbc;

    @LocalServerPort
    private int port;

    private final SecureRandom random = new SecureRandom();

    private void appendSome(int n) {
        for (int i = 0; i < n; i++) {
            byte[] payload = new byte[32];
            random.nextBytes(payload);
            tx.execute(status -> ledger.append(payload));
        }
    }

    private Map<String, Object> getMap(String path) {
        var response = rest.getForEntity(path, Map.class);
        assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static byte[] b64(Object value) {
        return Base64Url.decode((String) value);
    }

    private static List<byte[]> hashes(Object values) {
        return ((List<?>) values).stream().map(LedgerProofApiTests::b64).toList();
    }

    private Checkpoint checkpointFrom(Map<String, Object> body) {
        return new Checkpoint(((Number) body.get("size")).longValue(), b64(body.get("root")),
                ((Number) body.get("timestampMillis")).longValue(), b64(body.get("signature")));
    }

    private LedgerAuditor auditor() {
        return new LedgerAuditor(HttpClient.newHttpClient(), URI.create("http://localhost:" + port), new ObjectMapper());
    }

    @Test
    void theKeyAndASignedCheckpointArePublic() {
        appendSome(3);

        Map<String, Object> key = getMap("/api/ledger/key");
        Checkpoint checkpoint = checkpointFrom(getMap("/api/ledger/checkpoint"));

        assertThat(b64(key.get("publicKey"))).isEqualTo(signer.publicKey());
        assertThat(Ed25519.verify(signer.publicKey(), checkpoint.signedMessage(), checkpoint.signature())).isTrue();
        assertThat(checkpoint.size()).isGreaterThanOrEqualTo(3);
        List<byte[]> leaves = ledger.entries(1, (int) checkpoint.size()).stream()
                .map(e -> MerkleTree.leafHash(e.entryHash())).toList();
        assertThat(checkpoint.root()).isEqualTo(MerkleTree.root(leaves));
    }

    @Test
    void everyEntryHasAnInclusionProof() {
        appendSome(5);
        long size = ledger.size();

        // The first entry and the latest few (other test classes share this database).
        for (long seq : java.util.stream.LongStream.concat(java.util.stream.LongStream.of(1),
                java.util.stream.LongStream.rangeClosed(Math.max(2, size - 15), size)).toArray()) {
            Map<String, Object> body = getMap("/api/ledger/proof/inclusion?seq=" + seq);
            Checkpoint checkpoint = checkpointFrom((Map<String, Object>) body.get("checkpoint"));
            Map<String, Object> entry = (Map<String, Object>) body.get("entry");
            byte[] entryHash = LedgerHashing.entryHash(seq, b64(entry.get("prevHash")), b64(entry.get("payloadHash")),
                    java.time.Instant.ofEpochMilli(((Number) entry.get("recordedAtMillis")).longValue()));

            assertThat(entryHash).isEqualTo(b64(entry.get("entryHash")));
            assertThat(Ed25519.verify(signer.publicKey(), checkpoint.signedMessage(), checkpoint.signature())).isTrue();
            assertThat(MerkleTree.verifyInclusion(seq - 1, checkpoint.size(), MerkleTree.leafHash(entryHash),
                    hashes(body.get("proof")), checkpoint.root())).as("seq %d", seq).isTrue();
        }
        assertThat(rest.getForEntity("/api/ledger/proof/inclusion?seq=0", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/ledger/proof/inclusion?seq=" + (size + 1000), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aLaterCheckpointProvablyExtendsAnEarlierOne() {
        appendSome(2);
        Checkpoint before = checkpointFrom(getMap("/api/ledger/checkpoint"));
        appendSome(3);
        Checkpoint after = checkpointFrom(getMap("/api/ledger/checkpoint"));

        Map<String, Object> body = getMap("/api/ledger/proof/consistency?from=" + before.size() + "&to=" + after.size());

        assertThat(MerkleTree.verifyConsistency(before.size(), after.size(), before.root(), after.root(),
                hashes(body.get("proof")))).isTrue();
        assertThat(rest.getForEntity("/api/ledger/proof/consistency?from=0&to=1", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/ledger/proof/consistency?from=2&to=1", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/ledger/proof/consistency?from=1&to=" + (after.size() + 1000), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void checkpointsArePublishedOnlyWhenTheLedgerGrowsAndCannotBeChanged() {
        appendSome(1);
        Checkpoint published = checkpoints.publishIfGrown().orElseThrow();

        assertThat(checkpoints.publishIfGrown()).isEmpty();
        assertThat(checkpoints.latestPublished().orElseThrow().size()).isEqualTo(published.size());
        List<Map<String, Object>> listed = rest.getForEntity(
                "/api/ledger/checkpoints?after=" + (published.size() - 1), List.class).getBody();
        assertThat(listed).hasSize(1);
        assertThat(checkpointFrom(listed.getFirst()).root()).isEqualTo(published.root());
        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_checkpoints SET timestamp_millis = 0").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE ledger_checkpoints").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void anAuditorPinsTheKeyAndFollowsTheLedgerAsItGrows(@TempDir Path dir) throws Exception {
        appendSome(2);
        checkpoints.publishIfGrown();
        Path state = dir.resolve("witness.json");

        assertThat(AuditLedger.run(new String[] {"http://localhost:" + port, "--state", state.toString(), "--no-rekor"})).isZero();
        assertThat(Files.readString(state)).contains(Base64Url.encode(signer.publicKey()));

        appendSome(3);
        checkpoints.publishIfGrown();
        appendSome(1);

        assertThat(AuditLedger.run(new String[] {"http://localhost:" + port, "--state", state.toString(), "--no-rekor"})).isZero();
        assertThat(new ObjectMapper().readValue(state.toFile(), LedgerAuditor.WitnessState.class).size())
                .isEqualTo(ledger.size());
    }

    @Test
    void anAuditorRejectsADifferentKey() throws Exception {
        byte[] otherKey = new byte[32];
        random.nextBytes(otherKey);

        LedgerAuditor.Report report = auditor().audit(Optional.of(Base64Url.encode(otherKey)), Optional.empty());

        assertThat(report.ok()).isFalse();
        assertThat(report.lines()).anyMatch(line -> line.contains("ledger key changed"));
        assertThat(AuditLedger.run(new String[] {"--state"})).isEqualTo(2);
    }

    @Test
    void rewritingHistoryIsCaughtByTheAuditorAndTheConsistencyProof() throws Exception {
        appendSome(4);
        LedgerAuditor.Report first = auditor().audit(Optional.empty(), Optional.empty());
        assertThat(first.ok()).isTrue();
        Checkpoint before = checkpointFrom(getMap("/api/ledger/checkpoint"));
        long victim = before.size() - 1;
        byte[] original = ledger.entry(victim).orElseThrow().entryHash();
        byte[] forged = new byte[32];
        random.nextBytes(forged);
        appendSome(1);

        // Someone with raw database access rewrites an old entry, bypassing the append-only trigger.
        jdbc.sql("ALTER TABLE ledger_entries DISABLE TRIGGER ledger_entries_append_only").update();
        try {
            jdbc.sql("UPDATE ledger_entries SET entry_hash = :h WHERE seq = :seq")
                    .param("h", forged).param("seq", victim).update();

            Checkpoint after = checkpointFrom(getMap("/api/ledger/checkpoint"));
            Map<String, Object> body = getMap("/api/ledger/proof/consistency?from=" + before.size() + "&to="
                    + after.size());
            LedgerAuditor.Report audit = auditor().audit(Optional.empty(), Optional.of(first.state()));

            // The server still signs whatever it holds, but it cannot prove the new tree extends the old one.
            assertThat(Ed25519.verify(signer.publicKey(), after.signedMessage(), after.signature())).isTrue();
            assertThat(MerkleTree.verifyConsistency(before.size(), after.size(), before.root(), after.root(),
                    hashes(body.get("proof")))).isFalse();
            assertThat(audit.ok()).isFalse();
            assertThat(audit.lines()).anyMatch(line -> line.contains("history was rewritten or forked"));
        } finally {
            jdbc.sql("UPDATE ledger_entries SET entry_hash = :h WHERE seq = :seq")
                    .param("h", original).param("seq", victim).update();
            jdbc.sql("ALTER TABLE ledger_entries ENABLE TRIGGER ledger_entries_append_only").update();
        }
        assertThat(auditor().audit(Optional.empty(), Optional.of(first.state())).ok()).isTrue();
    }
}
