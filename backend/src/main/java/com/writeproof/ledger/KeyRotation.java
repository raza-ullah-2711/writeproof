package com.writeproof.ledger;

import com.writeproof.common.Base64Url;
import com.writeproof.identity.Ed25519;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A handover of the ledger key: the old key signs the new one and the checkpoint (size and root) the
 * new key starts from. The old key vouches for checkpoints up to {@code size}; the new key for
 * checkpoints from {@code size} on. Mirrored by the frontend's {@code key-rotation.ts}; pinned by a
 * shared test vector.
 */
public record KeyRotation(byte[] oldKey, byte[] newKey, long size, byte[] root, long timestampMillis,
                          byte[] signature) {

    public static final String DOMAIN = "writeproof/key-rotation/v1";

    /** No path of valid rotations leads from a pinned key to the server's key. */
    public static class BrokenChainException extends Exception {
        BrokenChainException(String message) {
            super(message);
        }
    }

    public static byte[] signedMessage(byte[] oldKey, byte[] newKey, long size, byte[] root, long timestampMillis) {
        return (DOMAIN + "\n" + Base64Url.encode(oldKey) + "\n" + Base64Url.encode(newKey) + "\n" + size + "\n"
                + Base64Url.encode(root) + "\n" + timestampMillis).getBytes(StandardCharsets.UTF_8);
    }

    public byte[] signedMessage() {
        return signedMessage(oldKey, newKey, size, root, timestampMillis);
    }

    /** True if the old key signed this rotation and it is well formed. */
    public boolean verify() {
        return size >= 0 && root != null && root.length == 32
                && Ed25519.verify(oldKey, signedMessage(), signature);
    }

    /**
     * The rotations leading from {@code pinned} to {@code current}, each signed by the key before it,
     * in order. Empty if the key did not change.
     *
     * @param all every rotation the server has made, oldest first
     * @throws BrokenChainException if the key changed but no valid chain of rotations explains it
     */
    public static List<KeyRotation> follow(byte[] pinned, byte[] current, List<KeyRotation> all)
            throws BrokenChainException {
        if (Arrays.equals(pinned, current)) {
            return List.of();
        }
        int start = 0;
        while (start < all.size() && !Arrays.equals(all.get(start).oldKey(), pinned)) {
            start++;
        }
        if (start == all.size()) {
            throw new BrokenChainException("ledger key changed from " + Base64Url.encode(pinned) + " to "
                    + Base64Url.encode(current) + " with no rotation signed by the old key");
        }
        List<KeyRotation> path = new ArrayList<>();
        byte[] key = pinned;
        long size = 0;
        for (KeyRotation r : all.subList(start, all.size())) {
            if (!Arrays.equals(r.oldKey(), key) || !r.verify()) {
                throw new BrokenChainException("ledger key rotation to " + Base64Url.encode(r.newKey())
                        + " is not signed by the key before it");
            }
            if (r.size() < size) {
                throw new BrokenChainException("ledger key rotation to " + Base64Url.encode(r.newKey())
                        + " starts before the rotation that preceded it");
            }
            path.add(r);
            key = r.newKey();
            size = r.size();
        }
        if (!Arrays.equals(key, current)) {
            throw new BrokenChainException("ledger key rotations end at " + Base64Url.encode(key)
                    + ", but the server's key is " + Base64Url.encode(current));
        }
        return path;
    }
}
