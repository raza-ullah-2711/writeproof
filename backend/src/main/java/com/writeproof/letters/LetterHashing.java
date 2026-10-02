package com.writeproof.letters;

import com.writeproof.common.Base64Url;
import com.writeproof.ledger.LedgerHashing;
import java.nio.charset.StandardCharsets;

/**
 * Canonical letter bytes, mirrored by the frontend's {@code letter-format.ts} and pinned by a
 * shared test vector.
 *
 * <ul>
 *   <li>{@link #headerV2} (v1: {@link #header}): binds sender, recipient, time and the handwriting
 *       hash; {@link #headerV3}, for replies, also the hash of the letter being answered. It is also the AES-GCM associated data, so a ciphertext can't be moved into a
 *       different letter.
 *   <li>{@link #letterHash}: SHA-256 over the header and every envelope field. This is what goes on
 *       the ledger.
 *   <li>{@link #signedMessage}: what the sender's identity key signs. Domain-separated from
 *       login messages.
 * </ul>
 */
public final class LetterHashing {

    /** Letters sent before hand-signing (Task 5); still readable and verifiable. */
    public static final String DOMAIN = "writeproof/letter/v1";
    /** Hand-signed letters: the header also commits to the handwriting sample's hash. */
    public static final String DOMAIN_V2 = "writeproof/letter/v2";
    /** Replies: a v2 header that also commits to the hash of the letter being answered. */
    public static final String DOMAIN_V3 = "writeproof/letter/v3";
    public static final String SIGNATURE_DOMAIN = "writeproof/letter-signature/v1";

    private LetterHashing() {}

    public static String header(byte[] senderKey, byte[] recipientKey, String sentAt) {
        return DOMAIN + "\n" + Base64Url.encode(senderKey) + "\n" + Base64Url.encode(recipientKey) + "\n" + sentAt;
    }

    public static String headerV2(byte[] senderKey, byte[] recipientKey, String sentAt, byte[] handwritingHash) {
        return DOMAIN_V2 + "\n" + Base64Url.encode(senderKey) + "\n" + Base64Url.encode(recipientKey) + "\n" + sentAt
                + "\n" + Base64Url.encode(handwritingHash);
    }

    public static String headerV3(byte[] senderKey, byte[] recipientKey, String sentAt, byte[] handwritingHash,
                                  byte[] inReplyTo) {
        return DOMAIN_V3 + "\n" + Base64Url.encode(senderKey) + "\n" + Base64Url.encode(recipientKey) + "\n" + sentAt
                + "\n" + Base64Url.encode(handwritingHash) + "\n" + Base64Url.encode(inReplyTo);
    }

    /** SHA-256 of the exact handwriting JSON as sent (and as sealed inside the letter). */
    public static byte[] handwritingHash(String handwritingJson) {
        return LedgerHashing.sha256(handwritingJson.getBytes(StandardCharsets.UTF_8));
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
