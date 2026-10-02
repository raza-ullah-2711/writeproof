package com.writeproof.ledger;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Merkle tree over the ledger, as in Certificate Transparency (RFC 9162 §2.1): leaves are
 * hashed as {@code SHA-256(0x00 || data)}, interior nodes as {@code SHA-256(0x01 || left || right)},
 * and a tree of n leaves splits at the largest power of two below n. Here a leaf's data is a
 * ledger entry's 32-byte entry hash. Mirrored by the frontend's {@code merkle.ts}.
 *
 * <p>Inclusion proofs show an entry is in a tree; consistency proofs show a larger tree extends a
 * smaller one unchanged. Both are O(log n) hashes.
 */
public final class MerkleTree {

    private MerkleTree() {}

    public static byte[] leafHash(byte[] data) {
        return sha256((byte) 0x00, data, null);
    }

    public static byte[] nodeHash(byte[] left, byte[] right) {
        return sha256((byte) 0x01, left, right);
    }

    /** The root over already-hashed leaves. The empty tree's root is SHA-256 of nothing. */
    public static byte[] root(List<byte[]> leafHashes) {
        if (leafHashes.isEmpty()) {
            return sha256NoPrefix();
        }
        return subtreeRoot(leafHashes, 0, leafHashes.size());
    }

    /** Audit path for leaf {@code index} in the tree of the first {@code size} leaves (RFC 9162 §2.1.3.1). */
    public static List<byte[]> inclusionProof(List<byte[]> leafHashes, int index, int size) {
        if (index < 0 || index >= size || size > leafHashes.size()) {
            throw new IllegalArgumentException("index must be in [0, size) and size <= leaves");
        }
        List<byte[]> proof = new ArrayList<>();
        path(leafHashes, index, 0, size, proof);
        return proof;
    }

    /** Proof that the tree of {@code oldSize} leaves is a prefix of the tree of {@code newSize} (§2.1.4.1). */
    public static List<byte[]> consistencyProof(List<byte[]> leafHashes, int oldSize, int newSize) {
        if (oldSize < 1 || oldSize > newSize || newSize > leafHashes.size()) {
            throw new IllegalArgumentException("need 1 <= oldSize <= newSize <= leaves");
        }
        List<byte[]> proof = new ArrayList<>();
        if (oldSize < newSize) {
            subproof(leafHashes, oldSize, 0, newSize, true, proof);
        }
        return proof;
    }

    /** RFC 9162 §2.1.3.2. */
    public static boolean verifyInclusion(long index, long size, byte[] leafHash, List<byte[]> proof, byte[] root) {
        if (index < 0 || index >= size) {
            return false;
        }
        long fn = index;
        long sn = size - 1;
        byte[] r = leafHash;
        for (byte[] p : proof) {
            if (sn == 0) {
                return false;
            }
            if ((fn & 1) == 1 || fn == sn) {
                r = nodeHash(p, r);
                if ((fn & 1) == 0) {
                    while ((fn & 1) == 0 && fn != 0) {
                        fn >>= 1;
                        sn >>= 1;
                    }
                }
            } else {
                r = nodeHash(r, p);
            }
            fn >>= 1;
            sn >>= 1;
        }
        return sn == 0 && Arrays.equals(r, root);
    }

    /** RFC 9162 §2.1.4.2. */
    public static boolean verifyConsistency(long oldSize, long newSize, byte[] oldRoot, byte[] newRoot,
                                            List<byte[]> proof) {
        if (oldSize < 1 || oldSize > newSize) {
            return false;
        }
        if (oldSize == newSize) {
            return proof.isEmpty() && Arrays.equals(oldRoot, newRoot);
        }
        List<byte[]> path = new ArrayList<>(proof);
        if (Long.bitCount(oldSize) == 1) {
            path.addFirst(oldRoot); // oldSize is a power of two: its root is a node of the new tree
        }
        if (path.isEmpty()) {
            return false;
        }
        long fn = oldSize - 1;
        long sn = newSize - 1;
        while ((fn & 1) == 1) {
            fn >>= 1;
            sn >>= 1;
        }
        byte[] fr = path.getFirst();
        byte[] sr = path.getFirst();
        for (byte[] c : path.subList(1, path.size())) {
            if (sn == 0) {
                return false;
            }
            if ((fn & 1) == 1 || fn == sn) {
                fr = nodeHash(c, fr);
                sr = nodeHash(c, sr);
                if ((fn & 1) == 0) {
                    while ((fn & 1) == 0 && fn != 0) {
                        fn >>= 1;
                        sn >>= 1;
                    }
                }
            } else {
                sr = nodeHash(sr, c);
            }
            fn >>= 1;
            sn >>= 1;
        }
        return sn == 0 && Arrays.equals(fr, oldRoot) && Arrays.equals(sr, newRoot);
    }

    private static byte[] subtreeRoot(List<byte[]> leaves, int from, int to) {
        int n = to - from;
        if (n == 1) {
            return leaves.get(from);
        }
        int k = largestPowerOfTwoBelow(n);
        return nodeHash(subtreeRoot(leaves, from, from + k), subtreeRoot(leaves, from + k, to));
    }

    private static void path(List<byte[]> leaves, int m, int from, int to, List<byte[]> out) {
        int n = to - from;
        if (n == 1) {
            return;
        }
        int k = largestPowerOfTwoBelow(n);
        if (m < k) {
            path(leaves, m, from, from + k, out);
            out.add(subtreeRoot(leaves, from + k, to));
        } else {
            path(leaves, m - k, from + k, to, out);
            out.add(subtreeRoot(leaves, from, from + k));
        }
    }

    private static void subproof(List<byte[]> leaves, int m, int from, int to, boolean complete, List<byte[]> out) {
        int n = to - from;
        if (m == n) {
            if (!complete) {
                out.add(subtreeRoot(leaves, from, to));
            }
            return;
        }
        int k = largestPowerOfTwoBelow(n);
        if (m <= k) {
            subproof(leaves, m, from, from + k, complete, out);
            out.add(subtreeRoot(leaves, from + k, to));
        } else {
            subproof(leaves, m - k, from + k, to, false, out);
            out.add(subtreeRoot(leaves, from, from + k));
        }
    }

    /** Largest power of two strictly less than n (n >= 2). */
    static int largestPowerOfTwoBelow(int n) {
        return Integer.highestOneBit(n - 1);
    }

    private static byte[] sha256(byte prefix, byte[] a, byte[] b) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(prefix);
            d.update(a);
            if (b != null) {
                d.update(b);
            }
            return d.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256NoPrefix() {
        try {
            return MessageDigest.getInstance("SHA-256").digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
