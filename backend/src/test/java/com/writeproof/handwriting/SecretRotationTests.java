package com.writeproof.handwriting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.writeproof.TestWallet;
import com.writeproof.WriteproofApplication;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Rotates HANDWRITING_DATA_KEY and JWT_SECRET across restarts against one database, as an operator
 * would: nothing stored becomes unreadable and nobody is signed out.
 */
class SecretRotationTests {

    private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private static final String DATA_A = secret(1);
    private static final String DATA_B = secret(2);
    private static final String JWT_1 = secret(3);
    private static final String JWT_2 = secret(4);

    private final SyntheticSignatures.Writer hand = new SyntheticSignatures.Writer(6006);

    @BeforeAll
    static void startDatabase() {
        DB.start();
    }

    @AfterAll
    static void stopDatabase() {
        DB.stop();
    }

    private static String secret(int b) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) b);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static ConfigurableApplicationContext start(String dataKey, String previousDataKeys, String jwt,
                                                        String previousJwt) {
        return new SpringApplicationBuilder(WriteproofApplication.class)
                .profiles("test")
                .run("--server.port=0",
                        "--spring.datasource.url=" + DB.getJdbcUrl(),
                        "--spring.datasource.username=" + DB.getUsername(),
                        "--spring.datasource.password=" + DB.getPassword(),
                        "--writeproof.handwriting.data-key=" + dataKey,
                        "--writeproof.handwriting.previous-data-keys=" + (previousDataKeys == null ? "" : previousDataKeys),
                        "--writeproof.auth.jwt-secret=" + jwt,
                        "--writeproof.auth.previous-jwt-secret=" + (previousJwt == null ? "" : previousJwt));
    }

    private static TestRestTemplate rest(ConfigurableApplicationContext app) {
        return new TestRestTemplate(new RestTemplateBuilder()
                .rootUri("http://localhost:" + app.getEnvironment().getProperty("local.server.port")));
    }

    private HttpStatus me(TestRestTemplate rest, String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return HttpStatus.valueOf(rest.exchange("/api/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class)
                .getStatusCode().value());
    }

    /** Verifies a fresh signature against the stored enrolment: it must still decrypt. */
    private boolean matches(TestRestTemplate rest, TestWallet wallet, long seed) {
        var response = rest.exchange("/api/handwriting/verify", HttpMethod.POST,
                new HttpEntity<>(Map.of("sample", hand.genuine(seed, "pen")), wallet.headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Boolean.TRUE.equals(response.getBody().get("match"));
    }

    @Test
    void rotatingBothSecretsKeepsHandwritingReadableAndSessionsValid() throws Exception {
        String oldToken;
        TestWallet wallet;
        try (var app = start(DATA_A, null, JWT_1, null)) {
            TestRestTemplate rest = rest(app);
            wallet = TestWallet.create(rest);
            assertThat(rest.exchange("/api/handwriting/enrolment", HttpMethod.POST, new HttpEntity<>(Map.of("samples",
                    List.of(hand.genuine(1, "pen"), hand.genuine(2, "pen"), hand.genuine(3, "pen"))), wallet.headers()),
                    Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(matches(rest, wallet, 4)).isTrue();
            oldToken = wallet.token;
        }

        // A new data key without the old one would make every enrolment unreadable: refuse to start.
        assertThatThrownBy(() -> start(DATA_B, null, JWT_1, null))
                .hasStackTraceContaining("can't decrypt the stored handwriting");

        try (var app = start(DATA_B, DATA_A, JWT_2, JWT_1)) {
            TestRestTemplate rest = rest(app);
            // The old session still works, and new sessions are signed with the new secret.
            assertThat(me(rest, oldToken)).isEqualTo(HttpStatus.OK);
            TestWallet again = TestWallet.create(rest, wallet.identity);
            assertThat(me(rest, again.token)).isEqualTo(HttpStatus.OK);
            assertThat(again.token).isNotEqualTo(oldToken);

            BiometricKeyRotation rotation = app.getBean(BiometricKeyRotation.class);
            assertThat(rotation.pending()).isPositive();
            // Still readable under the old key, before anything is rewritten.
            assertThat(matches(rest, again, 5)).isTrue();
            assertThat(rotation.reencryptPending()).isPositive();
            assertThat(rotation.pending()).isZero();
        }

        try (var app = start(DATA_B, null, JWT_2, null)) {
            TestRestTemplate rest = rest(app);
            // With the old secrets gone: old tokens are refused, re-encrypted handwriting still reads.
            assertThat(me(rest, oldToken)).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(app.getBean(BiometricKeyRotation.class).pending()).isZero();
            assertThat(matches(rest, TestWallet.create(rest, wallet.identity), 6)).isTrue();
        }
    }
}
