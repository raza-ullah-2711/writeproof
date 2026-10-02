package com.writeproof.contacts;

import com.writeproof.common.Base64Url;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The signed-in account's encrypted contact book. Opaque to the server: it checks only the size
 * and that writes don't race (each PUT names the version it was based on).
 */
@RestController
@RequestMapping("/api/me/contacts")
class ContactBookController {

    /** AES-GCM: 12-byte nonce + 16-byte tag around at most 256 KiB of plaintext. */
    static final int MIN_BYTES = 28;
    static final int MAX_BYTES = 256 * 1024 + MIN_BYTES;

    record ContactBook(long version, String ciphertext, Instant updatedAt) {}

    record WriteRequest(@PositiveOrZero long baseVersion, @NotBlank String ciphertext) {}

    private final ContactBookRepository books;
    private final Clock clock;

    ContactBookController(ContactBookRepository books, Clock clock) {
        this.books = books;
        this.clock = clock;
    }

    /** Version 0 with no ciphertext means no book has been saved yet. */
    @GetMapping
    ContactBook get(@AuthenticationPrincipal Jwt jwt) {
        return books.find(account(jwt))
                .map(b -> new ContactBook(b.version(), Base64Url.encode(b.ciphertext()), b.updatedAt()))
                .orElse(new ContactBook(0, null, null));
    }

    @PutMapping
    ContactBook put(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WriteRequest request) {
        byte[] ciphertext = Base64Url.decode(request.ciphertext());
        if (ciphertext.length < MIN_BYTES || ciphertext.length > MAX_BYTES) {
            throw new IllegalArgumentException("Contact book ciphertext must be " + MIN_BYTES + " to " + MAX_BYTES
                    + " bytes");
        }
        UUID account = account(jwt);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!books.write(account, request.baseVersion(), ciphertext, now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The contact book changed on another device; reload and try again");
        }
        return new ContactBook(request.baseVersion() + 1, request.ciphertext(), now);
    }

    private static UUID account(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
