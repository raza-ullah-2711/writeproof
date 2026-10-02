package com.writeproof.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.TestWallet;
import com.writeproof.TestcontainersConfiguration;
import com.writeproof.handwriting.HandwritingSample;
import com.writeproof.handwriting.SyntheticSignatures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings({"rawtypes", "unchecked"})
class CalibrationApiTests {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CalibrationRepository repository;

    @Autowired
    private ObjectMapper json;

    private TestWallet alice;
    private final SyntheticSignatures.Writer hand = new SyntheticSignatures.Writer(31);

    @BeforeEach
    void wallet() throws Exception {
        // Earlier tests' contributors would otherwise be imitation targets here.
        jdbc.sql("DELETE FROM calibration_contributors").update();
        alice = TestWallet.create(rest);
    }

    private ResponseEntity<Map> call(TestWallet as, HttpMethod method, String path, Object body) {
        return rest.exchange(path, method, new HttpEntity<>(body, as.headers()), Map.class);
    }

    private Map<String, Object> consent(TestWallet as) {
        return call(as, HttpMethod.POST, "/api/calibration/consent", null).getBody();
    }

    private ResponseEntity<Map> give(TestWallet as, String kind, Object target, HandwritingSample sample) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("kind", kind);
        body.put("targetId", target);
        body.put("sample", sample);
        return call(as, HttpMethod.POST, "/api/calibration/samples", body);
    }

    @Test
    void optingInAssignsAPracticeNameOnce() {
        assertThat(call(alice, HttpMethod.GET, "/api/calibration", null).getBody()).containsEntry("contributing", false);

        Map<String, Object> first = consent(alice);
        Map<String, Object> again = consent(alice);

        assertThat(first).containsEntry("contributing", true);
        assertThat((String) first.get("practiceName")).matches("[A-Z][a-z]+ [A-Z][a-z]+");
        assertThat(again.get("practiceName")).isEqualTo(first.get("practiceName"));
    }

    @Test
    void samplesNeedConsentAndAreCounted() {
        assertThat(give(alice, "genuine", null, hand.genuine(1, "pen")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        consent(alice);

        give(alice, "genuine", null, hand.genuine(1, "pen"));
        Map<String, Object> status = give(alice, "genuine", null, hand.genuine(2, "pen")).getBody();

        assertThat(status).containsEntry("genuineSamples", 2).containsEntry("forgerySamples", 0);
        assertThat(give(alice, "genuine", UUID.randomUUID(), hand.genuine(3, "pen")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void imitationTargetsAreOtherContributorsWithEnoughSamples() throws Exception {
        TestWallet bob = TestWallet.create(rest);
        consent(alice);
        String bobName = (String) consent(bob).get("practiceName");
        for (int i = 0; i < 3; i++) {
            give(alice, "genuine", null, hand.genuine(i, "pen"));
        }
        assertThat(call(alice, HttpMethod.GET, "/api/calibration/forgery-target", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND); // only Alice herself has samples

        SyntheticSignatures.Writer bobHand = new SyntheticSignatures.Writer(32);
        for (int i = 0; i < 2; i++) {
            give(bob, "genuine", null, bobHand.genuine(i, "pen"));
        }
        assertThat(call(alice, HttpMethod.GET, "/api/calibration/forgery-target", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND); // Bob has only 2
        give(bob, "genuine", null, bobHand.genuine(9, "pen"));

        Map<String, Object> target = call(alice, HttpMethod.GET, "/api/calibration/forgery-target", null).getBody();
        assertThat(target).containsEntry("practiceName", bobName);
        assertThat(((Map) target.get("sample")).get("format")).isEqualTo("writeproof.handwriting");

        assertThat(give(alice, "forgery", target.get("targetId"), hand.genuine(50, "pen")).getBody())
                .containsEntry("forgerySamples", 1);
        // Imitating yourself or nobody is refused.
        String myId = jdbc.sql("SELECT c.contributor_id::text FROM calibration_contributors c JOIN accounts a "
                + "ON a.id = c.account_id WHERE a.public_key = :k").param("k", alice.publicKey).query(String.class).single();
        assertThat(give(alice, "forgery", myId, hand.genuine(51, "pen")).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(give(alice, "forgery", UUID.randomUUID(), hand.genuine(52, "pen")).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // The target id is pseudonymous, not Bob's account id.
        String bobAccount = jdbc.sql("SELECT id::text FROM accounts WHERE public_key = :k")
                .param("k", bob.publicKey).query(String.class).single();
        assertThat(target.get("targetId")).isNotEqualTo(bobAccount);
    }

    @Test
    void withdrawingErasesEverythingIncludingImitationsOfYou() throws Exception {
        TestWallet bob = TestWallet.create(rest);
        consent(alice);
        consent(bob);
        SyntheticSignatures.Writer bobHand = new SyntheticSignatures.Writer(33);
        for (int i = 0; i < 3; i++) {
            give(bob, "genuine", null, bobHand.genuine(i, "pen"));
        }
        Object bobId = call(alice, HttpMethod.GET, "/api/calibration/forgery-target", null).getBody().get("targetId");
        give(alice, "forgery", bobId, hand.genuine(1, "pen"));

        assertThat(call(bob, HttpMethod.DELETE, "/api/calibration", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(call(bob, HttpMethod.GET, "/api/calibration", null).getBody()).containsEntry("contributing", false);
        assertThat(call(alice, HttpMethod.GET, "/api/calibration", null).getBody()).containsEntry("forgerySamples", 0);
        assertThat(jdbc.sql("SELECT count(*) FROM calibration_samples").query(Integer.class).single()).isZero();
    }

    @Test
    void samplesAreEncryptedAndTheExportIsPseudonymous() throws Exception {
        String practiceName = (String) consent(alice).get("practiceName");
        give(alice, "genuine", null, hand.genuine(1, "pen"));

        byte[] stored = jdbc.sql("SELECT sample_encrypted FROM calibration_samples").query(byte[].class).single();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("strokes");

        Path file = Files.createTempFile("calibration", ".json");
        assertThat(new CalibrationExport(repository, json, file.toString(), null).exportTo(file)).isEqualTo(1);
        String exported = Files.readString(file);
        CalibrationDataset dataset = json.readValue(exported, CalibrationDataset.class).validate();

        String accountId = jdbc.sql("SELECT id::text FROM accounts WHERE public_key = :k")
                .param("k", alice.publicKey).query(String.class).single();
        assertThat(dataset.samples()).hasSize(1);
        assertThat(dataset.samples().getFirst().sample().strokes()).isNotEmpty(); // decrypted
        assertThat(exported).doesNotContain(accountId).doesNotContain(practiceName);
    }
}
