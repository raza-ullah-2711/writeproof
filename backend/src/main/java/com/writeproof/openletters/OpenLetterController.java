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

    /** The author is identified by their public key only, never by account id. */
    record OpenLetterResponse(String letterHash, String author, String sentAt, String body, String signature,
                              String handwritingHash, double handwritingScore, LedgerRef ledger) {
        static OpenLetterResponse of(OpenLetter l) {
            return new OpenLetterResponse(Base64Url.encode(l.letterHash()), Base64Url.encode(l.authorKey()),
                    l.sentAt(), l.body(), Base64Url.encode(l.signature()), Base64Url.encode(l.handwritingHash()),
                    Math.round(l.handwritingScore() * 1000) / 1000.0, LedgerRef.of(l.ledgerEntry()));
        }
    }

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

    @GetMapping("/api/open-letters/{letterHash}")
    OpenLetterResponse get(@PathVariable String letterHash) {
        byte[] hash = Base64Url.decode(letterHash);
        if (hash.length != 32) {
            throw new IllegalArgumentException("A letter hash is 32 bytes");
        }
        return OpenLetterResponse.of(letters.get(hash));
    }
}
