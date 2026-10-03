package com.writeproof.openletters;

import com.writeproof.common.Base64Url;
import com.writeproof.ledger.LedgerEntry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publish and read open letters. Reading one needs no account, only its hash (the link). There is
 * no listing of other people's letters: no feed, no directory.
 */
@RestController
class OpenLetterController {

    record PublishRequest(
            @NotBlank String sentAt,
            @NotBlank String body,
            @NotBlank String signature,
            @NotBlank @Size(max = 500_000) String handwriting) {}

    record LedgerRef(long seq, String prevHash, String payloadHash, long recordedAtMillis, String entryHash) {
        static LedgerRef of(LedgerEntry e) {
            return new LedgerRef(e.seq(), Base64Url.encode(e.prevHash()), Base64Url.encode(e.payloadHash()),
                    e.recordedAt().toEpochMilli(), Base64Url.encode(e.entryHash()));
        }
    }

    /**
     * The author is identified by their public key only, never by account id. After a takedown
     * {@code body} is null and {@code removed} says when and why; the record itself remains.
     */
    record OpenLetterResponse(String letterHash, String author, String sentAt, String body, String signature,
                              String handwritingHash, double handwritingScore, LedgerRef ledger,
                              OpenLetter.Removal removed) {
        static OpenLetterResponse of(OpenLetter l) {
            return new OpenLetterResponse(Base64Url.encode(l.letterHash()), Base64Url.encode(l.authorKey()),
                    l.sentAt(), l.body(), Base64Url.encode(l.signature()), Base64Url.encode(l.handwritingHash()),
                    Math.round(l.handwritingScore() * 1000) / 1000.0, LedgerRef.of(l.ledgerEntry()), l.removal());
        }
    }

    record ReportRequest(@NotBlank String category, @Size(max = 500) String note) {}

    record AppealRequest(@NotBlank @Size(max = 1000) String text) {}

    private final OpenLetterService letters;

    OpenLetterController(OpenLetterService letters) {
        this.letters = letters;
    }

    @PostMapping("/api/me/open-letters")
    ResponseEntity<OpenLetterResponse> publish(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody PublishRequest request) {
        OpenLetter letter = letters.publish(UUID.fromString(jwt.getSubject()), request.sentAt(), request.body(),
                Base64Url.decode(request.signature()), request.handwriting());
        String hash = Base64Url.encode(letter.letterHash());
        return ResponseEntity.created(URI.create("/api/open-letters/" + hash)).body(OpenLetterResponse.of(letter));
    }

    @GetMapping("/api/me/open-letters")
    List<OpenLetterResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return letters.byAuthor(UUID.fromString(jwt.getSubject())).stream().map(OpenLetterResponse::of).toList();
    }

    /** The author appeals a takedown of their letter while it can still be restored. */
    @PostMapping("/api/me/open-letters/{letterHash}/appeal")
    ResponseEntity<Void> appeal(@AuthenticationPrincipal Jwt jwt, @PathVariable String letterHash,
                                @Valid @RequestBody AppealRequest request) {
        letters.appeal(UUID.fromString(jwt.getSubject()), hash32(letterHash), request.text());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/api/open-letters/{letterHash}")
    OpenLetterResponse get(@PathVariable String letterHash) {
        return OpenLetterResponse.of(letters.get(hash32(letterHash)));
    }

    /** Anyone who can read a letter can report it; signed-in readers are recorded (and counted once). */
    @PostMapping("/api/open-letters/{letterHash}/reports")
    ResponseEntity<Void> report(@AuthenticationPrincipal Jwt jwt, @PathVariable String letterHash,
                                @Valid @RequestBody ReportRequest request) {
        letters.report(hash32(letterHash), jwt == null ? null : UUID.fromString(jwt.getSubject()),
                request.category(), request.note());
        return ResponseEntity.accepted().build();
    }

    static byte[] hash32(String value) {
        byte[] hash = Base64Url.decode(value);
        if (hash.length != 32) {
            throw new IllegalArgumentException("A letter hash is 32 bytes");
        }
        return hash;
    }
}
