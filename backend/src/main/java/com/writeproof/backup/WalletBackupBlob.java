package com.writeproof.backup;

import com.writeproof.common.Base64Url;
import jakarta.validation.constraints.NotBlank;

/**
 * An encrypted wallet backup as produced by the browser ({@code wallet-backup.ts}). Only sizes and
 * the plaintext identity key are checked here; the server can't decrypt anything.
 *
 * @param publicKey the backed-up identity key (the account address), in the clear so a restore can
 *                  show which account it is and the server can check it belongs to the uploader
 */
public record WalletBackupBlob(
        String format,
        int version,
        @NotBlank String publicKey,
        @NotBlank String salt,
        @NotBlank String iv,
        @NotBlank String ciphertext,
        @NotBlank String createdAt) {

    public static final String FORMAT = "writeproof.wallet-backup";
    /** Two PKCS#8 keys, two public keys and JSON framing, plus the GCM tag, fit comfortably. */
    static final int MAX_CIPHERTEXT = 4096;

    /** @throws IllegalArgumentException if the blob is malformed */
    WalletBackupBlob validate() {
        if (!FORMAT.equals(format) || version != 1) {
            throw new IllegalArgumentException("Not a Writeproof wallet backup (v1)");
        }
        requireLength(publicKey, 32, "publicKey");
        requireLength(salt, 32, "salt");
        requireLength(iv, 12, "iv");
        int length = Base64Url.decode(ciphertext).length;
        if (length <= 16 || length > MAX_CIPHERTEXT) {
            throw new IllegalArgumentException("ciphertext must be 17 to " + MAX_CIPHERTEXT + " bytes");
        }
        if (createdAt.length() > 40) {
            throw new IllegalArgumentException("createdAt is too long");
        }
        return this;
    }

    private static void requireLength(String field, int bytes, String name) {
        if (Base64Url.decode(field).length != bytes) {
            throw new IllegalArgumentException(name + " must be " + bytes + " bytes");
        }
    }
}
