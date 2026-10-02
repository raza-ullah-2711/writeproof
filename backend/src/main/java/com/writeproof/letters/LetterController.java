package com.writeproof.letters;

import com.writeproof.common.Base64Url;
import com.writeproof.ledger.LedgerEntry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Send and read letters. There are intentionally no update or delete endpoints. */
@RestController
@RequestMapping("/api/letters")
class LetterController {

    record SendRequest(
            @NotBlank String recipientPublicKey,
            @NotBlank String sentAt,
            @NotNull @Valid LetterEnvelope envelope,
            @NotBlank String signature,
            /** The handwriting sample (writeproof.handwriting v1) as a JSON string, hashed byte-for-byte. */
            @NotBlank @Size(max = MAX_HANDWRITING_JSON) String handwriting) {}

    static final int MAX_HANDWRITING_JSON = 500_000;

    record Party(UUID accountId, String publicKey) {}

    record LedgerRef(long seq, String prevHash, String payloadHash, long recordedAtMillis, String entryHash) {
        static LedgerRef of(LedgerEntry e) {
            return new LedgerRef(e.seq(), Base64Url.encode(e.prevHash()), Base64Url.encode(e.payloadHash()),
                    e.recordedAt().toEpochMilli(), Base64Url.encode(e.entryHash()));
        }
    }

    /** {@code handwritingHash} and {@code handwritingScore} are null for v1 letters (before hand-signing). */
    record LetterResponse(UUID letterId, Party sender, Party recipient, String sentAt, LetterEnvelope envelope,
                          String signature, String letterHash, LedgerRef ledger, String handwritingHash,
                          Double handwritingScore) {
        static LetterResponse of(Letter l) {
            return new LetterResponse(
                    l.id(),
                    new Party(l.senderId(), Base64Url.encode(l.senderKey())),
                    new Party(l.recipientId(), Base64Url.encode(l.recipientKey())),
                    l.sentAt(),
                    l.envelope(),
                    Base64Url.encode(l.signature()),
                    Base64Url.encode(l.letterHash()),
                    LedgerRef.of(l.ledgerEntry()),
                    l.handSigned() ? Base64Url.encode(l.handwritingHash()) : null,
                    l.handwritingScore() == null ? null : Math.round(l.handwritingScore() * 1000) / 1000.0);
        }
    }

    private final LetterService letters;

    LetterController(LetterService letters) {
        this.letters = letters;
    }

    @PostMapping
    ResponseEntity<LetterResponse> send(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SendRequest request) {
        Letter letter = letters.send(
                accountId(jwt),
                Base64Url.decode(request.recipientPublicKey()),
                request.sentAt(),
                request.envelope(),
                Base64Url.decode(request.signature()),
                request.handwriting());
        return ResponseEntity.created(URI.create("/api/letters/" + letter.id())).body(LetterResponse.of(letter));
    }

    @GetMapping("/inbox")
    List<LetterResponse> inbox(@AuthenticationPrincipal Jwt jwt) {
        return letters.inbox(accountId(jwt)).stream().map(LetterResponse::of).toList();
    }

    @GetMapping("/sent")
    List<LetterResponse> sent(@AuthenticationPrincipal Jwt jwt) {
        return letters.sent(accountId(jwt)).stream().map(LetterResponse::of).toList();
    }

    @GetMapping("/{id}")
    LetterResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return LetterResponse.of(letters.get(accountId(jwt), id));
    }

    @ExceptionHandler(HandwritingRejectedException.class)
    ProblemDetail handwritingRejected(HandwritingRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getReason());
        problem.setProperty("score", Math.round(e.verification.score() * 1000) / 1000.0);
        problem.setProperty("threshold", e.verification.threshold());
        problem.setProperty("match", e.verification.match());
        problem.setProperty("livenessFlags", e.verification.livenessFlags());
        return problem;
    }

    private static UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
