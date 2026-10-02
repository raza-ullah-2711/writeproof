package com.writeproof;

import com.writeproof.auth.LoginMessage;
import com.writeproof.common.Base64Url;
import com.writeproof.identity.EncryptionKeyBinding;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/** A wallet driven through the real API, the way the browser does it. */
public final class TestWallet {

    public final KeyPair identity;
    public final byte[] publicKey;
    public final String token;

    private TestWallet(KeyPair identity, byte[] publicKey, String token) {
        this.identity = identity;
        this.publicKey = publicKey;
        this.token = token;
    }

    /** Registers a fresh Ed25519 wallet and logs it in; returns a bearer token. */
    public static String registerAndLogIn(TestRestTemplate rest) throws Exception {
        return create(rest).token;
    }

    public static TestWallet create(TestRestTemplate rest) throws Exception {
        KeyPair wallet = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] raw = raw(wallet);
        String publicKey = Base64Url.encode(raw);
        rest.postForEntity("/api/accounts", Map.of("publicKey", publicKey), Map.class);
        Map<?, ?> challenge = rest.postForEntity("/api/auth/challenge", Map.of("publicKey", publicKey), Map.class).getBody();
        byte[] signature = sign(wallet.getPrivate(), LoginMessage.of(
                UUID.fromString((String) challenge.get("challengeId")), Base64Url.decode((String) challenge.get("nonce"))));
        Map<?, ?> token = rest.postForEntity("/api/auth/verify",
                Map.of("challengeId", challenge.get("challengeId"), "signature", Base64Url.encode(signature)),
                Map.class).getBody();
        return new TestWallet(wallet, raw, (String) token.get("token"));
    }

    /** Generates an X25519 key, signs the binding with the identity key and registers it. */
    public byte[] registerEncryptionKey(TestRestTemplate rest) throws Exception {
        byte[] encryptionKey = raw(KeyPairGenerator.getInstance("X25519").generateKeyPair());
        byte[] binding = sign(identity.getPrivate(), EncryptionKeyBinding.of(publicKey, encryptionKey));
        rest.exchange("/api/me/encryption-key", HttpMethod.PUT, new HttpEntity<>(
                Map.of("encryptionKey", Base64Url.encode(encryptionKey), "signature", Base64Url.encode(binding)),
                headers()), Map.class);
        return encryptionKey;
    }

    public HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    public static byte[] sign(PrivateKey key, byte[] message) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(message);
        return signer.sign();
    }

    /** Raw 32-byte public key: the tail of the JDK's X.509 encoding (Ed25519 and X25519 alike). */
    public static byte[] raw(KeyPair keyPair) {
        byte[] spki = keyPair.getPublic().getEncoded();
        return Arrays.copyOfRange(spki, spki.length - 32, spki.length);
    }
}
