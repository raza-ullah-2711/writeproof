package com.writeproof.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.WriteproofApplication;
import com.writeproof.common.Base64Url;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Restarts the whole server against one database, as an operator rotating the ledger key would. */
class KeyRotationTests {

    private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private static final String A = seed(1);
    private static final String B = seed(2);
    private static final String C = seed(3);

    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void startDatabase() {
        DB.start();
    }

    @AfterAll
    static void stopDatabase() {
        DB.stop();
    }

    private static String seed(int b) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) b);
        return Base64.getEncoder().encodeToString(seed);
    }

    private static byte[] publicKey(String seed) {
        return new LedgerSigner(seed, "test key").publicKey();
    }

    private static ConfigurableApplicationContext start(String signingKey, String previousKey) {
        // Command-line arguments, so they win over application.yml and the test profile.
        return new SpringApplicationBuilder(WriteproofApplication.class)
                .profiles("test")
                .run("--server.port=0",
                        "--spring.datasource.url=" + DB.getJdbcUrl(),
                        "--spring.datasource.username=" + DB.getUsername(),
                        "--spring.datasource.password=" + DB.getPassword(),
                        "--writeproof.ledger.signing-key=" + signingKey,
                        "--writeproof.ledger.previous-signing-key=" + (previousKey == null ? "" : previousKey));
    }

    private static String url(ConfigurableApplicationContext app) {
        return "http://localhost:" + app.getEnvironment().getProperty("local.server.port");
    }

    private void append(ConfigurableApplicationContext app, int n) {
        LedgerService ledger = app.getBean(LedgerService.class);
        TransactionTemplate tx = app.getBean(TransactionTemplate.class);
        for (int i = 0; i < n; i++) {
            byte[] payload = new byte[32];
            random.nextBytes(payload);
            tx.execute(status -> ledger.append(payload));
        }
    }

    private JsonNode key(ConfigurableApplicationContext app) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url(app) + "/api/ledger/key")).build(),
                HttpResponse.BodyHandlers.ofString());
        return json.readTree(response.body());
    }

    private static int audit(ConfigurableApplicationContext app, Path state) {
        return AuditLedger.run(new String[] {url(app), "--state", state.toString()});
    }

    @Test
    void witnessesFollowARotatedKeyAndTheServerRefusesAKeyNobodyVouchedFor(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("witness.json");
        long sizeAtRotation;
        try (var app = start(A, null)) {
            append(app, 3);
            app.getBean(CheckpointService.class).publishIfGrown();
            assertThat(audit(app, state)).isZero();
            append(app, 2);
            sizeAtRotation = app.getBean(LedgerService.class).size();
            assertThat(key(app).path("rotations")).isEmpty();
        }

        // Swapping the key without a handover would make every client refuse the ledger: refuse to start.
        assertThatThrownBy(() -> start(B, null)).rootCause()
                .hasMessageContaining("LEDGER_SIGNING_KEY is not the ledger's key");
        assertThatThrownBy(() -> start(C, B)).rootCause()
                .hasMessageContaining("LEDGER_PREVIOUS_SIGNING_KEY is not the ledger's key");

        try (var app = start(B, A)) {
            JsonNode key = key(app);
            assertThat(key.path("publicKey").asText()).isEqualTo(Base64Url.encode(publicKey(B)));
            assertThat(key.path("rotations")).hasSize(1);
            JsonNode r = key.path("rotations").get(0);
            KeyRotation rotation = new KeyRotation(Base64Url.decode(r.path("oldKey").asText()),
                    Base64Url.decode(r.path("newKey").asText()), r.path("size").asLong(),
                    Base64Url.decode(r.path("root").asText()), r.path("timestampMillis").asLong(),
                    Base64Url.decode(r.path("signature").asText()));
            assertThat(rotation.verify()).isTrue();
            assertThat(rotation.oldKey()).isEqualTo(publicKey(A));
            assertThat(rotation.size()).isEqualTo(sizeAtRotation);
            assertThat(rotation.root()).isEqualTo(app.getBean(LedgerService.class).root(sizeAtRotation));

            append(app, 2);
            app.getBean(CheckpointService.class).publishIfGrown();

            // A witness that pinned the old key follows the handover and pins the new one.
            assertThat(audit(app, state)).isZero();
            assertThat(json.readValue(state.toFile(), LedgerAuditor.WitnessState.class).publicKey())
                    .isEqualTo(Base64Url.encode(publicKey(B)));
            // A new witness checks the whole history, signed by both keys in turn.
            LedgerAuditor fresh = new LedgerAuditor(HttpClient.newHttpClient(), URI.create(url(app)), json);
            LedgerAuditor.Report report = fresh.audit(Optional.empty(), Optional.empty());
            assertThat(report.ok()).as(String.join("\n", report.lines())).isTrue();
            assertThat(report.lines()).anyMatch(line -> line.contains("ledger key rotated"));
            // One given the old key by hand does too; one given an unrelated key does not.
            assertThat(fresh.audit(Optional.of(Base64Url.encode(publicKey(A))), Optional.empty()).ok()).isTrue();
            assertThat(fresh.audit(Optional.of(Base64Url.encode(publicKey(C))), Optional.empty()).lines())
                    .anyMatch(line -> line.contains("FAIL ledger key changed"));
        }

        // Restarting with the same settings, or without the previous key, changes nothing.
        try (var app = start(B, A)) {
            assertThat(key(app).path("rotations")).hasSize(1);
        }
        try (var app = start(B, null)) {
            assertThat(key(app).path("rotations")).hasSize(1);
            assertThat(audit(app, state)).isZero();
        }
        // A retired key never comes back.
        assertThatThrownBy(() -> start(A, B)).rootCause().hasMessageContaining("retired");
        assertThatThrownBy(() -> start(A, null)).rootCause()
                .hasMessageContaining("LEDGER_SIGNING_KEY is not the ledger's key");
    }
}
