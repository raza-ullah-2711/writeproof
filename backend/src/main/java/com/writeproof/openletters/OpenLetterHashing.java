package com.writeproof.openletters;

import com.writeproof.common.Base64Url;
import com.writeproof.ledger.LedgerHashing;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

    /** Version 2: a letter with handwritten pages (docs/handwritten-pages.md). */
    public static final String DOMAIN_V2 = "writeproof/open-letter/v2";
    public static final String PAGES_DOMAIN = "writeproof/open-letter-pages/v1";

    /** Commits to the pages in order and to whether the author let readers replay them. */
    public static byte[] pagesHash(boolean replay, List<byte[]> pageHashes) {
        StringBuilder preimage = new StringBuilder(PAGES_DOMAIN).append('\n').append(replay ? '1' : '0');
        for (byte[] pageHash : pageHashes) {
            preimage.append('\n').append(Base64Url.encode(pageHash));
        }
        return LedgerHashing.sha256(preimage.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** {@code body} is the transcript, possibly empty. */
    public static byte[] letterHashV2(byte[] authorKey, String sentAt, byte[] handwritingHash, String body,
                                      byte[] pagesHash) {
        String preimage = DOMAIN_V2 + "\n" + Base64Url.encode(authorKey) + "\n" + sentAt + "\n"
                + Base64Url.encode(handwritingHash) + "\n" + Base64Url.encode(bodyHash(body)) + "\n"
                + Base64Url.encode(pagesHash);
        return LedgerHashing.sha256(preimage.getBytes(StandardCharsets.UTF_8));
    }
}
