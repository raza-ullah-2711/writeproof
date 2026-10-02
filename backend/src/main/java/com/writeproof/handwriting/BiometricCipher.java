package com.writeproof.handwriting;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts stored handwriting at rest (AES-256-GCM). The associated data names the table and
 * account each value belongs to, so ciphertext copied into another row won't decrypt.
 * This protects database dumps and backups, not a compromised application (which holds the key).
 *
 * <p>Format: {@code 0x01 || 12-byte nonce || ciphertext+tag}. The leading byte versions the key,
 * so a rotation can add a second key without rewriting everything at once.
 */
@Component
public class BiometricCipher {

    private static final byte VERSION = 1;
    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    BiometricCipher(HandwritingProperties properties) {
        byte[] raw = Base64.getDecoder().decode(properties.dataKey());
        if (raw.length != 32) {
            throw new IllegalStateException("HANDWRITING_DATA_KEY must decode to exactly 32 bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] encrypt(String plaintext, String context) {
        byte[] nonce = new byte[NONCE];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(context));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + NONCE + ct.length).put(VERSION).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt handwriting data", e);
        }
    }

    public String decrypt(byte[] stored, String context) {
        if (stored == null || stored.length <= 1 + NONCE || stored[0] != VERSION) {
            throw new IllegalStateException("Stored handwriting data has an unknown format");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 1, NONCE));
            cipher.updateAAD(aad(context));
            byte[] pt = cipher.doFinal(stored, 1 + NONCE, stored.length - 1 - NONCE);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Stored handwriting data could not be decrypted", e);
        }
    }

    static String enrolmentContext(java.util.UUID accountId) {
        return "enrolment:" + accountId;
    }

    static String historyContext(java.util.UUID accountId) {
        return "history:" + accountId;
    }

    private static byte[] aad(String context) {
        return ("writeproof/biometric/v1\n" + context).getBytes(StandardCharsets.UTF_8);
    }
}
