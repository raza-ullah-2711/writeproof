package com.writeproof.ledger;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Holds the ledger key and signs checkpoints with it. */
@Component
public class LedgerSigner {

    private final PrivateKey privateKey;
    private final byte[] publicKey;

    @Autowired
    LedgerSigner(LedgerProperties properties) {
        this(properties.signingKey(), "LEDGER_SIGNING_KEY");
    }

    /** A signer for the base64 32-byte seed in the setting {@code name}. */
    LedgerSigner(String base64Seed, String name) {
        byte[] seed = Base64.getDecoder().decode(base64Seed);
        if (seed.length != 32) {
            throw new IllegalStateException(name + " must decode to exactly 32 bytes");
        }
        KeyPair pair = fromSeed(seed);
        this.privateKey = pair.getPrivate();
        byte[] spki = pair.getPublic().getEncoded();
        this.publicKey = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
    }

    /** Raw 32-byte Ed25519 public key: what clients pin and auditors check against. */
    public byte[] publicKey() {
        return publicKey.clone();
    }

    public byte[] sign(byte[] message) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(message);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Derives the key pair from its 32-byte seed. The JDK has no public API for this, but its
     * Ed25519 generator takes the private key straight from the random source, so a source that
     * yields exactly the seed reproduces the pair (verified by LedgerSignerTest against RFC 8032).
     */
    static KeyPair fromSeed(byte[] seed) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            generator.initialize(NamedParameterSpec.ED25519, new SecureRandom() {
                @Override
                public void nextBytes(byte[] bytes) {
                    System.arraycopy(seed, 0, bytes, 0, Math.min(seed.length, bytes.length));
                }
            });
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
