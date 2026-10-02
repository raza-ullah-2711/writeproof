package com.writeproof.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Ed25519Test {

    private static final byte[] MESSAGE = "sealed letter".getBytes(StandardCharsets.UTF_8);

    private KeyPair keyPair;
    private byte[] rawPublicKey;

    @BeforeEach
    void generateKeyPair() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        rawPublicKey = rawPublicKey(keyPair);
    }

    @Test
    void verifiesAGenuineSignature() throws Exception {
        assertThat(Ed25519.verify(rawPublicKey, MESSAGE, sign(keyPair, MESSAGE))).isTrue();
    }

    @Test
    void rejectsATamperedMessage() throws Exception {
        byte[] signature = sign(keyPair, MESSAGE);
        byte[] tampered = "sealed letteR".getBytes(StandardCharsets.UTF_8);

        assertThat(Ed25519.verify(rawPublicKey, tampered, signature)).isFalse();
    }

    @Test
    void rejectsASignatureFromAnotherKey() throws Exception {
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        assertThat(Ed25519.verify(rawPublicKey, MESSAGE, sign(other, MESSAGE))).isFalse();
    }

    @Test
    void treatsMalformedSignaturesAsInvalid() {
        assertThat(Ed25519.verify(rawPublicKey, MESSAGE, new byte[10])).isFalse();
        assertThat(Ed25519.verify(rawPublicKey, MESSAGE, new byte[64])).isFalse();
        assertThat(Ed25519.verify(rawPublicKey, MESSAGE, null)).isFalse();
    }

    @Test
    void rejectsPublicKeysOfTheWrongLength() {
        assertThatThrownBy(() -> Ed25519.decodePublicKey(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ed25519.decodePublicKey(new byte[33])).isInstanceOf(IllegalArgumentException.class);
    }

    /** The raw key is the last 32 bytes of the JDK's X.509 SubjectPublicKeyInfo encoding. */
    static byte[] rawPublicKey(KeyPair keyPair) {
        byte[] spki = keyPair.getPublic().getEncoded();
        return Arrays.copyOfRange(spki, spki.length - Ed25519.PUBLIC_KEY_LENGTH, spki.length);
    }

    static byte[] sign(KeyPair keyPair, byte[] message) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(message);
        return signer.sign();
    }
}
