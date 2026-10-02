package com.writeproof.openletters;

import com.writeproof.common.Base64Url;
import com.writeproof.ledger.LedgerHashing;
import java.nio.charset.StandardCharsets;

/**
 * Canonical bytes of an open letter, mirrored by the frontend's {@code open-letter-format.ts} and
 * pinned by a shared test vector. The letter hash is what goes on the ledger; the author's wallet
 * signs it with the same message format as sealed letters
 * ({@link com.writeproof.letters.LetterHashing#signedMessage}), and the distinct domain here keeps
 * an open letter's hash from ever equalling a sealed letter's.
 */
public final class OpenLetterHashing {

    public static final String DOMAIN = "writeproof/open-letter/v1";

    private OpenLetterHashing() {}

    public static byte[] bodyHash(String body) {
        return LedgerHashing.sha256(body.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] letterHash(byte[] authorKey, String sentAt, byte[] handwritingHash, String body) {
        String preimage = DOMAIN + "\n" + Base64Url.encode(authorKey) + "\n" + sentAt + "\n"
                + Base64Url.encode(handwritingHash) + "\n" + Base64Url.encode(bodyHash(body));
        return LedgerHashing.sha256(preimage.getBytes(StandardCharsets.UTF_8));
    }
}
