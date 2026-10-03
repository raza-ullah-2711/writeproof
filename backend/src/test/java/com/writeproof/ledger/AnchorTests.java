package com.writeproof.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** Anchoring checkpoints in a (fake) Rekor log, and a witness holding the ledger to its anchors. */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnchorTests {

    private static final FakeRekor LOG;

    static {
        try {
            LOG = new FakeRekor();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void anchorInTheFakeLog(DynamicPropertyRegistry registry) {
        registry.add("writeproof.ledger.anchor.rekor-url", LOG::url);
        registry.add("writeproof.ledger.anchor.interval", () -> "PT24H");
    }

    @AfterAll
    static void stopLog() {
        LOG.close();
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private CheckpointService checkpoints;

    @Autowired
    private AnchorService anchors;

    @Autowired
    private LedgerSigner signer;

    @Autowired
    private TransactionTemplate tx;

    @LocalServerPort
    private int port;

    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper json = new ObjectMapper();

    private void appendSome(int n) {
        for (int i = 0; i < n; i++) {
            byte[] payload = new byte[32];
            random.nextBytes(payload);
            tx.execute(status -> ledger.append(payload));
        }
    }

    private LedgerAuditor witness(Duration grace) {
        HttpClient http = HttpClient.newHttpClient();
        return new LedgerAuditor(http, URI.create("http://localhost:" + port), json,
                Optional.of(new Rekor(http, URI.create(LOG.url()), json)), grace);
    }

    @Test
    void witnessesHoldTheLedgerToItsAnchorsInThePublicLog() throws Exception {
        // Published checkpoints are anchored, and a witness verifies each anchor in the log itself.
        appendSome(3);
        checkpoints.publishIfGrown();
        assertThat(anchors.anchorPending()).isEqualTo(1);
        assertThat(anchors.anchorPending()).isZero();
        List<Map<String, Object>> listed = rest.getForObject("/api/ledger/anchors", List.class);
        assertThat(listed).isNotEmpty().allSatisfy(a -> assertThat(a).containsEntry("logUrl", LOG.url()))
                .anySatisfy(a -> assertThat(a).containsEntry("size", (int) ledger.size()));

        LedgerAuditor.Report first = witness(Duration.ZERO).audit(Optional.empty(), Optional.empty());
        assertThat(first.ok()).as(String.join("\n", first.lines())).isTrue();
        assertThat(first.lines()).anyMatch(l -> l.contains("new anchor(s) verified"))
                .anyMatch(l -> l.contains("holds no checkpoint under the ledger key that the server doesn't publish"));
        assertThat(first.state().logKey()).isNotNull();

        // A checkpoint left unanchored is tolerated within the grace period, not after it.
        appendSome(2);
        checkpoints.publishIfGrown();
        assertThat(witness(Duration.ofHours(1)).audit(Optional.empty(), Optional.of(first.state())).ok()).isTrue();
        LedgerAuditor.Report late = witness(Duration.ZERO).audit(Optional.empty(), Optional.of(first.state()));
        assertThat(late.ok()).isFalse();
        assertThat(late.lines()).anyMatch(l -> l.contains("was never anchored"));

        anchors.anchorPending();
        LedgerAuditor.Report second = witness(Duration.ZERO).audit(Optional.empty(), Optional.of(first.state()));
        assertThat(second.ok()).as(String.join("\n", second.lines())).isTrue();
        assertThat(second.lines()).anyMatch(l -> l.contains("1 new anchor(s) verified"));

        // The server signs and anchors a different history to show someone else, but doesn't publish it:
        // it is in the public log under the ledger key, where the witness finds it.
        byte[] forkedRoot = new byte[32];
        random.nextBytes(forkedRoot);
        Checkpoint fork = new Checkpoint(ledger.size(), forkedRoot, System.currentTimeMillis(),
                signer.sign(Checkpoint.signedMessage(ledger.size(), forkedRoot, System.currentTimeMillis())));
        byte[] payload = fork.signedMessage();
        new Rekor(HttpClient.newHttpClient(), URI.create(LOG.url()), json)
                .submit(payload, signer.sign(Rekor.pae(Rekor.PAYLOAD_TYPE, payload)), signer.publicKey());

        LedgerAuditor.Report caught = witness(Duration.ZERO).audit(Optional.empty(), Optional.of(second.state()));
        assertThat(caught.ok()).isFalse();
        assertThat(caught.lines()).anyMatch(l -> l.contains("may be showing someone a different history"));
    }

    @Test
    void browsersGetTheLatestAnchorWithItsLogEntryToVerify() throws Exception {
        appendSome(2);
        checkpoints.publishIfGrown();
        anchors.anchorPending();

        Map<String, Object> body = rest.getForObject("/api/ledger/anchor/latest", Map.class);
        Map<String, Object> c = (Map<String, Object>) body.get("checkpoint");
        Map<String, Object> e = (Map<String, Object>) body.get("entry");
        Map<String, Object> p = (Map<String, Object>) e.get("proof");
        java.util.HexFormat hex = java.util.HexFormat.of();
        Rekor.Entry entry = new Rekor.Entry((String) e.get("uuid"), ((Number) e.get("logIndex")).longValue(), 0,
                java.util.Base64.getDecoder().decode((String) e.get("body")),
                new Rekor.InclusionProof(((Number) p.get("logIndex")).longValue(), ((Number) p.get("treeSize")).longValue(),
                        hex.parseHex((String) p.get("rootHash")),
                        ((List<String>) p.get("hashes")).stream().map(hex::parseHex).toList(), (String) p.get("checkpoint")));
        byte[] message = Checkpoint.signedMessage(((Number) c.get("size")).longValue(),
                com.writeproof.common.Base64Url.decode((String) c.get("root")), ((Number) c.get("timestampMillis")).longValue());

        assertThat(body).containsEntry("logUrl", LOG.url());
        assertThat(((Number) c.get("size")).longValue()).isEqualTo(ledger.size());
        Rekor.verify(entry, new Rekor(HttpClient.newHttpClient(), URI.create(LOG.url()), json).publicKey(), message,
                signer.publicKey(), json);
    }

    @Test
    void aWitnessPinsThePublicLogsKey() throws Exception {
        appendSome(1);
        checkpoints.publishIfGrown();
        anchors.anchorPending();
        LedgerAuditor.Report first = witness(Duration.ofHours(1)).audit(Optional.empty(), Optional.empty());
        assertThat(first.ok()).as(String.join("\n", first.lines())).isTrue();

        LedgerAuditor.WitnessState otherLog = new LedgerAuditor.WitnessState(first.state().publicKey(),
                first.state().size(), first.state().root(), "c29tZW9uZSBlbHNl", first.state().anchoredSize());
        LedgerAuditor.Report report = witness(Duration.ofHours(1)).audit(Optional.empty(), Optional.of(otherLog));

        assertThat(report.ok()).isFalse();
        assertThat(report.lines()).anyMatch(l -> l.contains("public log's key changed"));
    }
}
