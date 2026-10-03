package com.writeproof;

import com.writeproof.auth.LoginMessage;
import com.writeproof.auth.Surface;
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
        return create(rest, KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
    }

    /** The bootstrap admin of the test profile (seed 0x42 x 32; see application-test.yml), signed in to the admin app. */
    public static TestWallet bootstrapAdmin(TestRestTemplate rest) throws Exception {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) 0x42);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        generator.initialize(java.security.spec.NamedParameterSpec.ED25519, new java.security.SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                System.arraycopy(seed, 0, bytes, 0, Math.min(seed.length, bytes.length));
            }
        });
        return create(rest, generator.generateKeyPair()).adminSession(rest);
    }

    /** Registers (if new) and logs the given wallet in to the public app. */
    public static TestWallet create(TestRestTemplate rest, KeyPair wallet) throws Exception {
        rest.postForEntity("/api/accounts", Map.of("publicKey", Base64Url.encode(raw(wallet))), Map.class);
        return logIn(rest, wallet, Surface.APP);
    }

    /** The same wallet signed in to the admin app, as the admin host's proxy marks it (see Surface). */
    public TestWallet adminSession(TestRestTemplate rest) throws Exception {
        return logIn(rest, identity, Surface.ADMIN);
    }

    private static TestWallet logIn(TestRestTemplate rest, KeyPair wallet, Surface surface) throws Exception {
        byte[] raw = raw(wallet);
        Map<?, ?> challenge = rest.postForEntity("/api/auth/challenge", Map.of("publicKey", Base64Url.encode(raw)),
                Map.class).getBody();
        byte[] signature = sign(wallet.getPrivate(), LoginMessage.of(
                UUID.fromString((String) challenge.get("challengeId")), Base64Url.decode((String) challenge.get("nonce"))));
        HttpHeaders headers = new HttpHeaders();
        if (surface == Surface.ADMIN) {
            headers.set(Surface.HEADER, "admin");
        }
        Map<?, ?> token = rest.postForEntity("/api/auth/verify", new HttpEntity<>(
                Map.of("challengeId", challenge.get("challengeId"), "signature", Base64Url.encode(signature)), headers),
                Map.class).getBody();
        return new TestWallet(wallet, raw, token == null ? null : (String) token.get("token"));
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
