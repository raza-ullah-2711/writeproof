package com.writeproof.handwriting;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts stored handwriting at rest (AES-256-GCM). The associated data names the table and
 * account each value belongs to, so ciphertext copied into another row won't decrypt.
 * This protects database dumps and backups, not a compromised application (which holds the key).
 *
 * <p>Format: {@code 0x02 || key id (4 bytes) || 12-byte nonce || ciphertext+tag}, always under the
 * current key ({@code HANDWRITING_DATA_KEY}). Values written before rotation existed are
 * {@code 0x01 || nonce || ciphertext+tag}. Both decrypt under the current key or any previous one
 * ({@code HANDWRITING_PREVIOUS_DATA_KEYS}), so a key can be rotated while
 * {@link BiometricKeyRotation} re-encrypts the stored rows. See docs/security.md.
 */
@Component
public class BiometricCipher {

    private static final byte LEGACY = 1;
    private static final byte VERSION = 2;
    private static final int KEY_ID = 4;
    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;

    private record Key(byte[] id, SecretKey secret) {}

    private final Key current;
    /** The current key first, then the previous ones. */
    private final List<Key> keys;
    private final SecureRandom random = new SecureRandom();

    BiometricCipher(HandwritingProperties properties) {
        this.current = key(properties.dataKey(), "HANDWRITING_DATA_KEY");
        List<Key> all = new ArrayList<>(List.of(current));
        String previous = properties.previousDataKeys();
        if (previous != null) {
            for (String k : previous.split(",")) {
                if (!k.isBlank()) {
                    all.add(key(k.trim(), "HANDWRITING_PREVIOUS_DATA_KEYS"));
                }
            }
        }
        this.keys = List.copyOf(all);
    }

    public byte[] encrypt(String plaintext, String context) {
        byte[] nonce = new byte[NONCE];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, current.secret(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(context));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + KEY_ID + NONCE + ct.length)
                    .put(VERSION).put(current.id()).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt handwriting data", e);
        }
    }

    public String decrypt(byte[] stored, String context) {
        if (stored != null && stored.length > 1 + KEY_ID + NONCE && stored[0] == VERSION) {
            byte[] id = Arrays.copyOfRange(stored, 1, 1 + KEY_ID);
            Key key = keys.stream().filter(k -> Arrays.equals(k.id(), id)).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Stored handwriting data is encrypted under a key that isn't configured"));
            String plaintext = open(key, stored, 1 + KEY_ID, context);
            if (plaintext == null) {
                throw new IllegalStateException("Stored handwriting data could not be decrypted");
            }
            return plaintext;
        }
        if (stored != null && stored.length > 1 + NONCE && stored[0] == LEGACY) {
            // Written before keys had ids: whichever configured key opens it.
            for (Key key : keys) {
                String plaintext = open(key, stored, 1, context);
                if (plaintext != null) {
                    return plaintext;
                }
            }
            throw new IllegalStateException("Stored handwriting data could not be decrypted with any configured key");
        }
        throw new IllegalStateException("Stored handwriting data has an unknown format");
    }

    /** True if {@code stored} is already encrypted under the current key in the current format. */
    public boolean isCurrent(byte[] stored) {
        return stored != null && stored.length > 1 + KEY_ID && stored[0] == VERSION
                && Arrays.equals(Arrays.copyOfRange(stored, 1, 1 + KEY_ID), current.id());
    }

    /** The current key's id, as stored after the version byte. */
    byte[] currentKeyId() {
        return current.id().clone();
    }

    /** Null if this key doesn't open the value (GCM checks the key and the associated data). */
    private static String open(Key key, byte[] stored, int nonceAt, String context) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key.secret(), new GCMParameterSpec(TAG_BITS, stored, nonceAt, NONCE));
            cipher.updateAAD(aad(context));
            byte[] pt = cipher.doFinal(stored, nonceAt + NONCE, stored.length - nonceAt - NONCE);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private static Key key(String base64, String name) {
        byte[] raw = Base64.getDecoder().decode(base64);
        if (raw.length != 32) {
            throw new IllegalStateException(name + " must decode to exactly 32 bytes");
        }
        try {
            // A key's id is a hash of it under its own label: it names the key without revealing it.
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update("writeproof/biometric-key-id\n".getBytes(StandardCharsets.UTF_8));
            byte[] id = Arrays.copyOf(sha.digest(raw), KEY_ID);
            return new Key(id, new SecretKeySpec(raw, "AES"));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String enrolmentContext(UUID accountId) {
        return "enrolment:" + accountId;
    }

    static String historyContext(UUID accountId) {
        return "history:" + accountId;
    }

    public static String calibrationContext(UUID contributorId) {
        return "calibration:" + contributorId;
    }

    /** Content preserved for law enforcement (moderation): the same at-rest protection. */
    public static String preservationContext(UUID authorId) {
        return "preservation:" + authorId;
    }

    private static byte[] aad(String context) {
        return ("writeproof/biometric/v1\n" + context).getBytes(StandardCharsets.UTF_8);
    }
}
