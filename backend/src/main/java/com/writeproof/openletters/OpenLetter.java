package com.writeproof.openletters;

import com.writeproof.ledger.LedgerEntry;
import java.util.UUID;

public record OpenLetter(byte[] letterHash, UUID authorId, byte[] authorKey, String sentAt, String body,
                         byte[] signature, byte[] handwritingHash, double handwritingScore, LedgerEntry ledgerEntry) {}
