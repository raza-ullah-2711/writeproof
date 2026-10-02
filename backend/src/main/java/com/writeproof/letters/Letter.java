package com.writeproof.letters;

import com.writeproof.ledger.LedgerEntry;
import java.util.UUID;

/** A stored letter with both parties' identity keys and its ledger entry. */
public record Letter(
        UUID id,
        UUID senderId,
        byte[] senderKey,
        UUID recipientId,
        byte[] recipientKey,
        String sentAt,
        LetterEnvelope envelope,
        byte[] signature,
        byte[] letterHash,
        LedgerEntry ledgerEntry,
        byte[] handwritingHash,
        Double handwritingScore,
        byte[] inReplyTo,
        byte[] threadId) {

    /** v1 letters (sent before hand-signing) have no handwriting. */
    public boolean handSigned() {
        return handwritingHash != null;
    }

    /** Replies (v3) commit to the hash of the letter they answer. */
    public boolean isReply() {
        return inReplyTo != null;
    }
}
