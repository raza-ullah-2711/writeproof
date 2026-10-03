package com.writeproof.openletters;

import com.writeproof.ledger.LedgerEntry;
import java.time.Instant;
import java.util.UUID;

/** {@code body} is null and {@code removal} set once a moderator took the letter down. */
public record OpenLetter(byte[] letterHash, UUID authorId, byte[] authorKey, String sentAt, String body,
                         byte[] signature, byte[] handwritingHash, double handwritingScore, LedgerEntry ledgerEntry,
                         Removal removal) {

    public record Removal(String category, Instant at) {}

    public boolean removed() {
        return removal != null;
    }
}
