package com.writeproof.ledger;

import com.writeproof.common.Base64Url;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * How entry hashes are computed. Mirrored by the frontend's {@code ledger-verify.ts} so
 * recipients can check the chain themselves; pinned by a shared test vector.
 */
public final class LedgerHashing {

    public static final String DOMAIN = "writeproof/ledger/v1";
    public static final byte[] GENESIS_PREV_HASH = new byte[32];

    private LedgerHashing() {}

    public static byte[] entryHash(long seq, byte[] prevHash, byte[] payloadHash, Instant recordedAt) {
        String preimage = DOMAIN + "\n" + seq + "\n" + Base64Url.encode(prevHash) + "\n"
                + Base64Url.encode(payloadHash) + "\n" + recordedAt.toEpochMilli();
        return sha256(preimage.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
