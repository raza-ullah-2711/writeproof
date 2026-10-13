package com.writeproof.archive;

import com.writeproof.common.Base64Url;
import com.writeproof.ledger.LedgerHashing;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Canonical bytes of a private family-archive entry (docs/family-archive.md), mirrored by the
 * frontend's {@code archive-format.ts} and pinned by a shared test vector. The entry hash goes on
 * the ledger; the owner's wallet signs it with the same message format as letters
 * ({@link com.writeproof.letters.LetterHashing#signedMessage}). The distinct domain keeps it from
 * ever equalling a letter's hash.
 */
public final class ArchiveHashing {

    public static final String DOMAIN = "writeproof/archive/v1";

    /** One reader's copy of the content key, wrapped to their encryption key. */
    public record Wrap(byte[] readerKey, byte[] ephemeralPublicKey, byte[] iv, byte[] wrappedKey) {}

    private ArchiveHashing() {}

    /** Binds the owner, the time and the exact encrypted photos, in order; also the description's AAD. */
    public static String header(byte[] ownerKey, String addedAt, List<byte[]> blobHashes) {
        StringJoiner blobs = new StringJoiner(",");
        blobHashes.forEach(h -> blobs.add(Base64Url.encode(h)));
        return DOMAIN + "\n" + Base64Url.encode(ownerKey) + "\n" + addedAt + "\n" + blobs;
    }

    /** SHA-256 over the header, the encrypted description, and every reader's wrapped key, in order. */
    public static byte[] entryHash(String header, byte[] iv, byte[] ciphertext, List<Wrap> readers) {
        List<String> parts = new ArrayList<>();
        parts.add(header);
        parts.add(Base64Url.encode(iv));
        parts.add(Base64Url.encode(ciphertext));
        for (Wrap w : readers) {
            parts.add(Base64Url.encode(w.readerKey()));
            parts.add(Base64Url.encode(w.ephemeralPublicKey()));
            parts.add(Base64Url.encode(w.iv()));
            parts.add(Base64Url.encode(w.wrappedKey()));
        }
        return LedgerHashing.sha256(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
    }
}
