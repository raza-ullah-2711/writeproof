package com.writeproof;

import com.writeproof.auth.LoginMessage;
import com.writeproof.common.Base64Url;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;

/** Registers a fresh Ed25519 wallet and logs it in through the real API; returns a bearer token. */
public final class TestWallet {

    private TestWallet() {}

    public static String registerAndLogIn(TestRestTemplate rest) throws Exception {
        KeyPair wallet = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] spki = wallet.getPublic().getEncoded();
        String publicKey = Base64Url.encode(Arrays.copyOfRange(spki, spki.length - 32, spki.length));
        rest.postForEntity("/api/accounts", Map.of("publicKey", publicKey), Map.class);
        Map<?, ?> challenge = rest.postForEntity("/api/auth/challenge", Map.of("publicKey", publicKey), Map.class).getBody();
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(wallet.getPrivate());
        signer.update(LoginMessage.of(
                UUID.fromString((String) challenge.get("challengeId")), Base64Url.decode((String) challenge.get("nonce"))));
        Map<?, ?> token = rest.postForEntity("/api/auth/verify",
                Map.of("challengeId", challenge.get("challengeId"), "signature", Base64Url.encode(signer.sign())),
                Map.class).getBody();
        return (String) token.get("token");
    }
}
