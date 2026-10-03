package com.writeproof.openletters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.handwriting.HandwritingRejectedException;
import com.writeproof.handwriting.HandwritingSample;
import com.writeproof.handwriting.HandwritingService;
import com.writeproof.identity.Account;
import com.writeproof.identity.AccountRepository;
import com.writeproof.identity.AccountStatus;
import com.writeproof.identity.Ed25519;
import com.writeproof.ledger.LedgerEntry;
import com.writeproof.ledger.LedgerService;
import com.writeproof.letters.LetterHashing;
import com.writeproof.letters.LetterService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Publishing an open letter is the only write, and it is final: signed by the author's wallet and
 * hand, recorded on the ledger, and public to anyone with its hash.
 */
@Service
public class OpenLetterService {

    public static final int MAX_BODY_LENGTH = 10_000;

    private final AccountRepository accounts;
    private final OpenLetterRepository letters;
    private final LedgerService ledger;
    private final HandwritingService handwritingService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AccountStatus accountStatus;

    OpenLetterService(AccountRepository accounts, OpenLetterRepository letters, LedgerService ledger,
                      HandwritingService handwritingService, ObjectMapper objectMapper, Clock clock,
                      AccountStatus accountStatus) {
        this.accountStatus = accountStatus;
        this.accounts = accounts;
        this.letters = letters;
        this.ledger = ledger;
        this.handwritingService = handwritingService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public OpenLetter publish(UUID authorId, String sentAt, String body, byte[] signature, String handwritingJson) {
        if (body.isBlank() || body.codePointCount(0, body.length()) > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("An open letter needs 1 to " + MAX_BODY_LENGTH + " characters");
        }
        HandwritingSample handwriting = parseHandwriting(handwritingJson);
        byte[] handwritingHash = LetterHashing.handwritingHash(handwritingJson);
        Instant now = clock.instant();
        LetterService.requireCurrent(sentAt, now);
        Account author = accounts.findById(authorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
        accountStatus.requireCanSend(author.id());

        byte[] hash = OpenLetterHashing.letterHash(author.publicKey(), sentAt, handwritingHash, body);
        if (!Ed25519.verify(author.publicKey(), LetterHashing.signedMessage(hash), signature)) {
            throw unprocessable("Signature does not verify against the author's key");
        }
        if (letters.exists(hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This letter was already published");
        }
        if (letters.handwritingUsed(handwritingHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This signature already sealed another letter");
        }
        // Only after the wallet signature checks out, so only the key holder can probe scores.
        if (handwritingService.enrolment(author.id()).isEmpty()) {
            throw unprocessable("Enrol your handwriting before publishing letters");
        }
        HandwritingService.Verification verification = handwritingService.verifyForLetter(author.id(), handwriting);
        if (!verification.verified()) {
            throw new HandwritingRejectedException(verification);
        }

        LedgerEntry entry = ledger.append(hash);
        OpenLetter letter = new OpenLetter(hash, author.id(), author.publicKey(), sentAt, body, signature,
                handwritingHash, verification.score(), entry, null);
        letters.insert(letter, now);
        handwritingService.recordLetterSignature(author.id(), handwriting);
        return letter;
    }

    public OpenLetter get(byte[] letterHash) {
        return letters.find(letterHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such open letter"));
    }

    /** Report categories, shared with moderation (and the open_letter_reports check constraint). */
    public static final List<String> REPORT_CATEGORIES = List.of("spam", "harassment", "illegal", "impersonation",
            "other");

    /**
     * Files a reader's report. {@code reporterId} is null for readers without an account; a
     * signed-in reader can report each letter once.
     */
    public void report(byte[] letterHash, UUID reporterId, String category, String note) {
        if (!REPORT_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("category must be one of " + REPORT_CATEGORIES);
        }
        String trimmed = note == null || note.isBlank() ? null : note.trim();
        if (trimmed != null && trimmed.length() > 500) {
            throw new IllegalArgumentException("A note can be at most 500 characters");
        }
        OpenLetter letter = get(letterHash);
        if (letter.removed()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This letter was already removed");
        }
        if (!letters.report(letterHash, reporterId, category, trimmed, clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already reported this letter");
        }
    }

    public List<OpenLetter> byAuthor(UUID authorId) {
        return letters.byAuthor(authorId);
    }

    private HandwritingSample parseHandwriting(String json) {
        try {
            return objectMapper.readValue(json, HandwritingSample.class).validate();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("handwriting must be a Writeproof handwriting sample (JSON)");
        }
    }

    private static ResponseStatusException unprocessable(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }
}
