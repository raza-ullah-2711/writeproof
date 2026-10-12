package com.writeproof.openletters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.common.Base64Url;
import com.writeproof.handwriting.FeatureExtractor;
import com.writeproof.handwriting.HandwritingRejectedException;
import com.writeproof.handwriting.HandwritingSample;
import com.writeproof.handwriting.HandwritingService;
import com.writeproof.handwriting.LivenessChecker;
import com.writeproof.handwriting.LivenessFlag;
import com.writeproof.identity.Account;
import com.writeproof.identity.AccountRepository;
import com.writeproof.identity.AccountStatus;
import com.writeproof.identity.Ed25519;
import com.writeproof.ledger.LedgerEntry;
import com.writeproof.ledger.LedgerService;
import com.writeproof.letters.LetterHashing;
import com.writeproof.letters.LetterService;
import com.writeproof.system.SystemSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    public static final int MAX_PAGES = 4;
    /** A page must have been written this recently: no publishing old or collected strokes. */
    public static final Duration MAX_PAGE_AGE = Duration.ofHours(12);

    private final AccountRepository accounts;
    private final OpenLetterRepository letters;
    private final LedgerService ledger;
    private final HandwritingService handwritingService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AccountStatus accountStatus;
    private final SystemSettings settings;

    OpenLetterService(AccountRepository accounts, OpenLetterRepository letters, LedgerService ledger,
                      HandwritingService handwritingService, ObjectMapper objectMapper, Clock clock,
                      AccountStatus accountStatus, SystemSettings settings) {
        this.accountStatus = accountStatus;
        this.settings = settings;
        this.accounts = accounts;
        this.letters = letters;
        this.ledger = ledger;
        this.handwritingService = handwritingService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** A typed open letter (v1). */
    @Transactional
    public OpenLetter publish(UUID authorId, String sentAt, String body, byte[] signature, String handwritingJson) {
        return publish(authorId, sentAt, body, signature, handwritingJson, List.of(), false);
    }

    /**
     * Publishes an open letter. With pages it is a v2 letter: {@code body} is the optional
     * transcript, each page must be fresh, written live and never published before, and the letter
     * hash commits to the pages and the {@code replay} choice (docs/handwritten-pages.md).
     */
    @Transactional
    public OpenLetter publish(UUID authorId, String sentAt, String body, byte[] signature, String handwritingJson,
                              List<HandwrittenPage> pages, boolean replay) {
        settings.requireOpenLettersEnabled();
        boolean handwritten = !pages.isEmpty();
        if (body.codePointCount(0, body.length()) > MAX_BODY_LENGTH || (!handwritten && body.isBlank())) {
            throw new IllegalArgumentException(handwritten
                    ? "A transcript can have at most " + MAX_BODY_LENGTH + " characters"
                    : "An open letter needs 1 to " + MAX_BODY_LENGTH + " characters");
        }
        if (pages.size() > MAX_PAGES) {
            throw new IllegalArgumentException("An open letter can have at most " + MAX_PAGES + " pages");
        }
        if (replay && !handwritten) {
            throw new IllegalArgumentException("Only handwritten pages can be replayed");
        }
        HandwritingSample handwriting = parseHandwriting(handwritingJson);
        byte[] handwritingHash = LetterHashing.handwritingHash(handwritingJson);
        Instant now = clock.instant();
        LetterService.requireCurrent(sentAt, now);
        Account author = accounts.findById(authorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
        accountStatus.requireCanSend(author.id());

        List<byte[]> inkHashes = new ArrayList<>();
        List<byte[]> pageHashes = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            HandwrittenPage page = checkPage(pages.get(i), i + 1, now);
            byte[] inkHash = page.inkHash();
            inkHashes.add(inkHash);
            pageHashes.add(HandwrittenPage.pageHash(inkHash, page.computeTimingHash()));
        }
        byte[] hash = handwritten
                ? OpenLetterHashing.letterHashV2(author.publicKey(), sentAt, handwritingHash, body,
                        OpenLetterHashing.pagesHash(replay, pageHashes))
                : OpenLetterHashing.letterHash(author.publicKey(), sentAt, handwritingHash, body);
        if (!Ed25519.verify(author.publicKey(), LetterHashing.signedMessage(hash), signature)) {
            throw unprocessable("Signature does not verify against the author's key");
        }
        if (letters.exists(hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This letter was already published");
        }
        if (letters.handwritingUsed(handwritingHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This signature already sealed another letter");
        }
        Set<String> distinct = new HashSet<>();
        inkHashes.forEach(h -> distinct.add(Base64Url.encode(h)));
        if (distinct.size() != inkHashes.size() || letters.pageUsed(inkHashes)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A page can be published only once");
        }
        // Only after the wallet signature checks out, so only the key holder can probe scores.
        if (handwritingService.enrolment(author.id()).isEmpty()) {
            throw unprocessable("Enrol your handwriting before publishing letters");
        }
        HandwritingService.Verification verification = handwritingService.verifyForLetter(author.id(), handwriting);
        if (!verification.verified()) {
            throw new HandwritingRejectedException(verification);
        }
        // Whoever wrote the pages, they must look written live by a hand.
        for (int i = 0; i < pages.size(); i++) {
            requireLive(pages.get(i), i + 1);
        }

        LedgerEntry entry = ledger.append(hash);
        List<HandwrittenPage> published = new ArrayList<>();
        for (HandwrittenPage page : pages) {
            byte[] timingHash = page.computeTimingHash();
            published.add(replay ? page.withTimingHash(timingHash) : page.withoutTiming(timingHash));
        }
        OpenLetter letter = new OpenLetter(hash, author.id(), author.publicKey(), sentAt, body, signature,
                handwritingHash, verification.score(), entry, null, handwritten ? 2 : 1, replay, published);
        letters.insert(letter, now);
        letters.insertPages(hash, pages, replay);
        handwritingService.recordLetterSignature(author.id(), handwriting);
        return letter;
    }

    public OpenLetter get(byte[] letterHash) {
        return letters.find(letterHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such open letter"));
    }

    /** Report categories, shared with moderation (and the open_letter_reports check constraint). */
    public static final List<String> REPORT_CATEGORIES = List.of("spam", "harassment", "illegal", "impersonation",
            "child_safety", "copyright", "other");

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

    /**
     * The author appeals a takedown while it is still a hold (docs/launch-policies.md, T4): the text
     * then waits for a moderator's decision instead of being deleted when the window ends.
     */
    public void appeal(UUID authorId, byte[] letterHash, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty() || trimmed.length() > 1000) {
            throw new IllegalArgumentException("An appeal needs 1 to 1000 characters");
        }
        if (!letters.appeal(letterHash, authorId, trimmed, clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "There's no takedown of your letter that can still be appealed");
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

    /** The format and limits, and that the page was written recently (docs/handwritten-pages.md). */
    private static HandwrittenPage checkPage(HandwrittenPage page, int number, Instant now) {
        if (page == null) {
            throw new IllegalArgumentException("Page " + number + " is missing");
        }
        try {
            page.validate(true);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Page " + number + ": " + e.getMessage());
        }
        Instant captured = page.capturedInstant();
        if (captured.isBefore(now.minus(MAX_PAGE_AGE)) || captured.isAfter(now.plus(Duration.ofMinutes(2)))) {
            throw unprocessable("Page " + number + " must have been written in the last "
                    + MAX_PAGE_AGE.toHours() + " hours");
        }
        return page;
    }

    /** Run only after the wallet signature verified, like the signature's own checks. */
    private static void requireLive(HandwrittenPage page, int number) {
        var sample = page.toSample();
        Set<LivenessFlag> flags = LivenessChecker.check(sample, FeatureExtractor.extract(sample));
        if (!flags.isEmpty()) {
            throw unprocessable(flags.contains(LivenessFlag.INSUFFICIENT_INPUT)
                    ? "Page " + number + " has too little writing to check"
                    : "Page " + number + " doesn't look written live by a hand " + flags);
        }
    }

    private static ResponseStatusException unprocessable(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }
}
