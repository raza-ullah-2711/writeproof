package com.writeproof.identity;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;

/** Ed25519 signature verification over raw 32-byte public keys, using the JDK provider. */
public final class Ed25519 {

    public static final int PUBLIC_KEY_LENGTH = 32;
    public static final int SIGNATURE_LENGTH = 64;

    /** DER SubjectPublicKeyInfo header for an Ed25519 key (OID 1.3.101.112); the raw key follows. */
    private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private Ed25519() {}

    /** @throws IllegalArgumentException if {@code raw} is not a decodable Ed25519 public key */
    public static PublicKey decodePublicKey(byte[] raw) {
        if (raw == null || raw.length != PUBLIC_KEY_LENGTH) {
            throw new IllegalArgumentException("Ed25519 public key must be " + PUBLIC_KEY_LENGTH + " bytes");
        }
        byte[] spki = Arrays.copyOf(SPKI_PREFIX, SPKI_PREFIX.length + PUBLIC_KEY_LENGTH);
        System.arraycopy(raw, 0, spki, SPKI_PREFIX.length, PUBLIC_KEY_LENGTH);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid Ed25519 public key", e);
        }
    }

    /** Returns {@code true} only for a valid signature; malformed input verifies as {@code false}. */
    public static boolean verify(byte[] rawPublicKey, byte[] message, byte[] signature) {
        if (signature == null || signature.length != SIGNATURE_LENGTH) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(decodePublicKey(rawPublicKey));
            verifier.update(message);
            return verifier.verify(signature);
        } catch (IllegalArgumentException | InvalidKeyException | SignatureException e) {
            return false;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available in this JVM", e);
        }
    }
}
