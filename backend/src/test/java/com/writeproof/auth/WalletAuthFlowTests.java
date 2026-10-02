package com.writeproof.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.writeproof.TestcontainersConfiguration;
import com.writeproof.common.Base64Url;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WalletAuthFlowTests {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    private KeyPair wallet;
    private String publicKey;

    @BeforeEach
    void newWallet() throws Exception {
        wallet = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] spki = wallet.getPublic().getEncoded();
        publicKey = Base64Url.encode(Arrays.copyOfRange(spki, spki.length - 32, spki.length));
    }

    @Test
    void registerThenLogInWithASignedChallenge() throws Exception {
        ResponseEntity<Map> registered = post("/api/accounts", Map.of("publicKey", publicKey));
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String accountId = (String) registered.getBody().get("accountId");

        Map<?, ?> challenge = post("/api/auth/challenge", Map.of("publicKey", publicKey)).getBody();
        String signature = signChallenge(challenge, wallet);
        ResponseEntity<Map> verified = post(
                "/api/auth/verify", Map.of("challengeId", challenge.get("challengeId"), "signature", signature));

        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) verified.getBody().get("token");
        ResponseEntity<Map> me = getMe(token);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).containsEntry("accountId", accountId).containsEntry("publicKey", publicKey);
    }

    @Test
    void aChallengeCannotBeReplayed() throws Exception {
        register();
        Map<?, ?> challenge = post("/api/auth/challenge", Map.of("publicKey", publicKey)).getBody();
        Map<String, Object> answer =
                Map.of("challengeId", challenge.get("challengeId"), "signature", signChallenge(challenge, wallet));

        assertThat(post("/api/auth/verify", answer).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/auth/verify", answer).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aSignatureFromAnotherKeyIsRejectedAndBurnsTheChallenge() throws Exception {
        register();
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Map<?, ?> challenge = post("/api/auth/challenge", Map.of("publicKey", publicKey)).getBody();

        ResponseEntity<Map> forged = post("/api/auth/verify",
                Map.of("challengeId", challenge.get("challengeId"), "signature", signChallenge(challenge, attacker)));
        ResponseEntity<Map> genuineAfterwards = post("/api/auth/verify",
                Map.of("challengeId", challenge.get("challengeId"), "signature", signChallenge(challenge, wallet)));

        assertThat(forged.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(genuineAfterwards.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anExpiredChallengeIsRejected() throws Exception {
        register();
        Map<?, ?> challenge = post("/api/auth/challenge", Map.of("publicKey", publicKey)).getBody();
        jdbc.sql("UPDATE auth_challenges SET expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", UUID.fromString((String) challenge.get("challengeId")))
                .update();

        ResponseEntity<Map> verified = post("/api/auth/verify",
                Map.of("challengeId", challenge.get("challengeId"), "signature", signChallenge(challenge, wallet)));

        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aPublicKeyCanOnlyBeRegisteredOnce() {
        register();

        assertThat(post("/api/accounts", Map.of("publicKey", publicKey)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void malformedPublicKeysAreRejected() {
        assertThat(post("/api/accounts", Map.of("publicKey", Base64Url.encode(new byte[31]))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/accounts", Map.of("publicKey", "not base64url!")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void challengesAreOnlyIssuedForRegisteredKeys() {
        assertThat(post("/api/auth/challenge", Map.of("publicKey", publicKey)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void protectedEndpointsRequireAValidToken() {
        assertThat(getMe(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getMe("not.a.jwt").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void register() {
        assertThat(post("/api/accounts", Map.of("publicKey", publicKey)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<Map> post(String path, Map<String, ?> body) {
        return rest.postForEntity(path, body, Map.class);
    }

    private ResponseEntity<Map> getMe(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange("/api/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private static String signChallenge(Map<?, ?> challenge, KeyPair keyPair) throws Exception {
        byte[] message = LoginMessage.of(
                UUID.fromString((String) challenge.get("challengeId")),
                Base64Url.decode((String) challenge.get("nonce")));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(message);
        return Base64Url.encode(signer.sign());
    }
}
