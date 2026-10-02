package com.writeproof.letters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.handwriting.HandwritingSample;
import com.writeproof.handwriting.HandwritingService;
import com.writeproof.identity.Account;
import com.writeproof.identity.AccountRepository;
import com.writeproof.identity.Ed25519;
import com.writeproof.ledger.LedgerEntry;
import com.writeproof.ledger.LedgerService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sending is the only write. There is deliberately no way to edit or unsend a letter: once its
 * hash is on the ledger it is sealed. Every new letter is signed twice: by the sender's wallet
 * and by their hand.
 */
@Service
public class LetterService {

    /** Exactly what {@code Date.prototype.toISOString()} produces, so the signed bytes are unambiguous. */
    private static final Pattern SENT_AT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final AccountRepository accounts;
    private final LetterRepository letters;
    private final LedgerService ledger;
    private final HandwritingService handwritingService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    LetterService(AccountRepository accounts, LetterRepository letters, LedgerService ledger,
                  HandwritingService handwritingService, ObjectMapper objectMapper, Clock clock) {
        this.accounts = accounts;
        this.letters = letters;
        this.ledger = ledger;
        this.handwritingService = handwritingService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Sends a hand-signed letter. {@code handwritingJson} is the exact JSON of the signature the
     * sender wrote; its hash is part of the signed header, and it must verify against the sender's
     * enrolled handwriting (match, liveness, freshness, not a copy of a recent signature).
     */
    @Transactional
    public Letter send(UUID senderId, byte[] recipientKey, String sentAt, LetterEnvelope envelope, byte[] signature,
                       String handwritingJson) {
        envelope.validate();
        HandwritingSample handwriting = parseHandwriting(handwritingJson);
        byte[] handwritingHash = LetterHashing.handwritingHash(handwritingJson);
        Instant now = clock.instant();
        if (!SENT_AT.matcher(sentAt).matches()
                || Duration.between(Instant.parse(sentAt), now).abs().compareTo(MAX_CLOCK_SKEW) > 0) {
            throw unprocessable("sentAt must be the current time as ISO-8601 UTC with milliseconds");
        }
        Account sender = accounts.findById(senderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
        Account recipient = accounts.findByPublicKey(recipientKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No account with this address"));
        if (!sender.canReceiveLetters() || !recipient.canReceiveLetters()) {
            throw unprocessable("Both sender and recipient need a registered encryption key");
        }

        byte[] hash = LetterHashing.letterHash(
                LetterHashing.headerV2(sender.publicKey(), recipient.publicKey(), sentAt, handwritingHash), envelope);
        if (!Ed25519.verify(sender.publicKey(), LetterHashing.signedMessage(hash), signature)) {
            throw unprocessable("Signature does not verify against the sender's key");
        }
        if (letters.existsByHash(hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This letter was already sent");
        }
        if (letters.existsByHandwritingHash(handwritingHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This signature already sealed another letter");
        }

        // Only after the wallet signature checks out, so only the key holder can probe scores.
        if (handwritingService.enrolment(sender.id()).isEmpty()) {
            throw unprocessable("Enrol your handwriting before sending letters");
        }
        HandwritingService.Verification verification = handwritingService.verifyForLetter(sender.id(), handwriting);
        if (!verification.verified()) {
            throw new HandwritingRejectedException(verification);
        }

        LedgerEntry entry = ledger.append(hash);
        UUID id = UUID.randomUUID();
        letters.insert(id, sender.id(), recipient.id(), sentAt, envelope, signature, hash, entry.seq(), now,
                handwritingHash, verification.score());
        handwritingService.recordLetterSignature(sender.id(), handwriting);
        return new Letter(id, sender.id(), sender.publicKey(), recipient.id(), recipient.publicKey(), sentAt,
                envelope, signature, hash, entry, handwritingHash, verification.score());
    }

    private HandwritingSample parseHandwriting(String json) {
        try {
            return objectMapper.readValue(json, HandwritingSample.class).validate();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("handwriting must be a Writeproof handwriting sample (JSON)");
        }
    }

    /** Only the sender and the recipient can fetch a letter; to anyone else it doesn't exist. */
    public Letter get(UUID accountId, UUID letterId) {
        return letters.findById(letterId)
                .filter(l -> l.senderId().equals(accountId) || l.recipientId().equals(accountId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such letter"));
    }

    public List<Letter> inbox(UUID accountId) {
        return letters.inbox(accountId);
    }

    public List<Letter> sent(UUID accountId) {
        return letters.sent(accountId);
    }

    private static ResponseStatusException unprocessable(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }
}
