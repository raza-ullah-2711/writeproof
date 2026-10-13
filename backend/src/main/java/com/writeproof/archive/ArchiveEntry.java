package com.writeproof.archive;

import com.writeproof.ledger.LedgerEntry;
import java.util.List;

/**
 * A private family-archive entry as stored: everything a reader needs to check it and open it.
 * The server can't read any of it.
 *
 * @param ownerKey  the owner's identity key (their address)
 * @param blobHashes the encrypted photos, in order
 * @param readers   the owner first, then everyone it is shared with
 */
public record ArchiveEntry(byte[] entryHash, byte[] ownerKey, String addedAt, byte[] iv, byte[] ciphertext,
                           byte[] signature, LedgerEntry ledgerEntry, List<byte[]> blobHashes,
                           List<ArchiveHashing.Wrap> readers) {}
