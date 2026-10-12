package com.writeproof.openletters;

import com.writeproof.ledger.LedgerEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code body} (and {@code pages}) are null and {@code removal} set once a moderator took the letter
 * down (or its author withdrew it). During a takedown's appeal window the text still exists but is
 * hidden here too.
 *
 * @param version 1 for a typed letter, 2 for one with handwritten pages (docs/handwritten-pages.md)
 * @param replay  the author let readers watch the pages being written (v2 only)
 * @param pages   the handwritten pages as published: each with its timing hash, and its timing only
 *                when {@code replay}; empty for v1
 */
public record OpenLetter(byte[] letterHash, UUID authorId, byte[] authorKey, String sentAt, String body,
                         byte[] signature, byte[] handwritingHash, double handwritingScore, LedgerEntry ledgerEntry,
                         Removal removal, int version, boolean replay, List<HandwrittenPage> pages) {

    /**
     * @param appealUntil set while the takedown is a hold: the text is kept and the author may appeal
     *                    until then; null once the text is gone
     * @param appealed    the author appealed and a moderator hasn't decided yet
     */
    public record Removal(String category, Instant at, Instant appealUntil, boolean appealed) {}

    public boolean removed() {
        return removal != null;
    }
}
