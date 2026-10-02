package com.writeproof.letters;

import com.writeproof.common.Base64Url;
import com.writeproof.ledger.LedgerHashing;
import java.nio.charset.StandardCharsets;

/**
 * Canonical letter bytes, mirrored by the frontend's {@code letter-format.ts} and pinned by a
 * shared test vector.
 *
 * <ul>
 *   <li>{@link #header}: binds sender, recipient and time. It is also the AES-GCM associated
 *       data, so a ciphertext can't be moved into a different letter.
 *   <li>{@link #letterHash}: SHA-256 over the header and every envelope field. This is what goes on
 *       the ledger.
 *   <li>{@link #signedMessage}: what the sender's identity key signs. Domain-separated from
 *       login messages.
 * </ul>
 */
public final class LetterHashing {

    public static final String DOMAIN = "writeproof/letter/v1";
    public static final String SIGNATURE_DOMAIN = "writeproof/letter-signature/v1";

    private LetterHashing() {}

    public static String header(byte[] senderKey, byte[] recipientKey, String sentAt) {
        return DOMAIN + "\n" + Base64Url.encode(senderKey) + "\n" + Base64Url.encode(recipientKey) + "\n" + sentAt;
    }

    public static byte[] letterHash(String header, LetterEnvelope e) {
        String preimage = String.join("\n",
                header,
                e.iv(),
                e.ciphertext(),
                e.recipientKey().ephemeralPublicKey(),
                e.recipientKey().iv(),
                e.recipientKey().wrappedKey(),
                e.senderKey().ephemeralPublicKey(),
                e.senderKey().iv(),
                e.senderKey().wrappedKey());
        return LedgerHashing.sha256(preimage.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] signedMessage(byte[] letterHash) {
        return (SIGNATURE_DOMAIN + "\n" + Base64Url.encode(letterHash)).getBytes(StandardCharsets.UTF_8);
    }
}
