package com.writeproof.letters;

import com.writeproof.common.Base64Url;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A sealed letter body. All fields are unpadded base64url; the server checks their sizes but
 * can't decrypt anything. The body is AES-256-GCM under a random content key, which is wrapped
 * separately for the recipient and the sender (ephemeral X25519 + HKDF-SHA256 + AES-GCM).
 */
public record LetterEnvelope(
        int version,
        @NotBlank String iv,
        @NotBlank String ciphertext,
        @NotNull @Valid WrappedKey recipientKey,
        @NotNull @Valid WrappedKey senderKey) {

    /** 64 KiB of plaintext plus the 16-byte GCM tag. */
    public static final int MAX_CIPHERTEXT = 64 * 1024 + 16;
    /** A letter with handwritten pages (v4): up to 4 pages of strokes travel sealed inside. */
    public static final int MAX_CIPHERTEXT_WITH_PAGES = 2560 * 1024 + 16;

    public record WrappedKey(@NotBlank String ephemeralPublicKey, @NotBlank String iv, @NotBlank String wrappedKey) {

        void validate() {
            requireLength(ephemeralPublicKey, 32, "ephemeralPublicKey");
            requireLength(iv, 12, "iv");
            requireLength(wrappedKey, 32 + 16, "wrappedKey");
        }
    }

    /** @throws IllegalArgumentException if any field has the wrong size or encoding */
    public LetterEnvelope validate() {
        return validate(MAX_CIPHERTEXT);
    }

    /** As {@link #validate()}, allowing up to {@code maxCiphertext} bytes of ciphertext. */
    public LetterEnvelope validate(int maxCiphertext) {
        if (version != 1) {
            throw new IllegalArgumentException("Unsupported envelope version");
        }
        requireLength(iv, 12, "iv");
        int length = Base64Url.decode(ciphertext).length;
        if (length <= 16 || length > maxCiphertext) {
            throw new IllegalArgumentException("Ciphertext must be 17 to " + maxCiphertext + " bytes");
        }
        recipientKey.validate();
        senderKey.validate();
        return this;
    }

    private static void requireLength(String field, int bytes, String name) {
        if (Base64Url.decode(field).length != bytes) {
            throw new IllegalArgumentException(name + " must be " + bytes + " bytes");
        }
    }
}
