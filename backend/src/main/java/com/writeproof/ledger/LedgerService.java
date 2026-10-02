package com.writeproof.ledger;

import java.util.List;
import java.util.Optional;

/**
 * The append-only ledger. No blockchain has been chosen yet; {@link MockLedgerService} is a
 * hash-chained table. Implementations must never update or remove an entry.
 */
public interface LedgerService {

    /** Appends a 32-byte payload hash (e.g. a letter hash). Joins the caller's transaction. */
    LedgerEntry append(byte[] payloadHash);

    Optional<LedgerEntry> entry(long seq);

    /** Entries with {@code seq >= fromSeq}, in order, at most {@code limit}. */
    List<LedgerEntry> entries(long fromSeq, int limit);

    Optional<LedgerEntry> head();

    /** Re-walks the whole chain. */
    ChainCheck verify();

    /** Number of entries, i.e. the size of the Merkle tree over them. */
    long size();

    /** Merkle root over the first {@code size} entries' hashes (see {@link MerkleTree}). */
    byte[] root(long size);

    /** Proof that entry {@code seq} is in the tree of the first {@code treeSize} entries. */
    List<byte[]> inclusionProof(long seq, long treeSize);

    /** Proof that the tree of {@code oldSize} entries is a prefix of the tree of {@code newSize}. */
    List<byte[]> consistencyProof(long oldSize, long newSize);

    /** @param brokenAt first sequence number whose hash or link doesn't check out, or null */
    record ChainCheck(boolean intact, long length, Long brokenAt) {}
}
