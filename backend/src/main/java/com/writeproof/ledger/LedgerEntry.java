package com.writeproof.ledger;

import java.time.Instant;

/** One link of the chain. {@code entryHash} commits to everything else, including {@code prevHash}. */
public record LedgerEntry(long seq, byte[] prevHash, byte[] payloadHash, Instant recordedAt, byte[] entryHash) {}
