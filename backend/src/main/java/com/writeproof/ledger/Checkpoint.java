package com.writeproof.ledger;

import com.writeproof.common.Base64Url;
import java.nio.charset.StandardCharsets;

/**
 * A signed statement of the ledger's state: its Merkle root at {@code size} entries. Mirrored by
 * the frontend's {@code checkpoint.ts}; pinned by a shared test vector.
 */
public record Checkpoint(long size, byte[] root, long timestampMillis, byte[] signature) {

    public static final String DOMAIN = "writeproof/checkpoint/v1";

    public static byte[] signedMessage(long size, byte[] root, long timestampMillis) {
        return (DOMAIN + "\n" + size + "\n" + Base64Url.encode(root) + "\n" + timestampMillis)
                .getBytes(StandardCharsets.UTF_8);
    }

    public byte[] signedMessage() {
        return signedMessage(size, root, timestampMillis);
    }
}
