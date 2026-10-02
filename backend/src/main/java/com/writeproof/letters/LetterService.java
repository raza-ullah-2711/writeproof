package com.writeproof.letters;

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
 * hash is on the ledger it is sealed.
 */
@Service
public class LetterService {

    /** Exactly what {@code Date.prototype.toISOString()} produces, so the signed bytes are unambiguous. */
    private static final Pattern SENT_AT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final AccountRepository accounts;
    private final LetterRepository letters;
    private final LedgerService ledger;
    private final Clock clock;

    LetterService(AccountRepository accounts, LetterRepository letters, LedgerService ledger, Clock clock) {
        this.accounts = accounts;
        this.letters = letters;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Transactional
    public Letter send(UUID senderId, byte[] recipientKey, String sentAt, LetterEnvelope envelope, byte[] signature) {
        envelope.validate();
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

        byte[] hash = LetterHashing.letterHash(LetterHashing.header(sender.publicKey(), recipient.publicKey(), sentAt), envelope);
        if (!Ed25519.verify(sender.publicKey(), LetterHashing.signedMessage(hash), signature)) {
            throw unprocessable("Signature does not verify against the sender's key");
        }
        if (letters.existsByHash(hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This letter was already sent");
        }

        LedgerEntry entry = ledger.append(hash);
        UUID id = UUID.randomUUID();
        letters.insert(id, sender.id(), recipient.id(), sentAt, envelope, signature, hash, entry.seq(), now);
        return new Letter(id, sender.id(), sender.publicKey(), recipient.id(), recipient.publicKey(), sentAt,
                envelope, signature, hash, entry);
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
