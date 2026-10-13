package com.writeproof.archive;

import com.writeproof.common.Base64Url;
import com.writeproof.ledger.LedgerEntry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The private family archive. Upload each encrypted photo, then add the entry that signs them.
 * Only the owner and the people it's shared with can list or fetch anything.
 */
@RestController
class ArchiveController {

    record ReaderRequest(@NotBlank String publicKey, @NotBlank String ephemeralPublicKey, @NotBlank String iv,
                         @NotBlank String wrappedKey) {}

    record AddRequest(
            @NotBlank String addedAt,
            @NotBlank String iv,
            @NotBlank String ciphertext,
            @NotNull @Size(min = 1, max = ArchiveService.MAX_PHOTOS) List<String> photos,
            @NotNull @Size(min = 1, max = ArchiveService.MAX_READERS) List<@Valid ReaderRequest> readers,
            @NotBlank String signature) {}

    record LedgerRef(long seq, String prevHash, String payloadHash, long recordedAtMillis, String entryHash) {
        static LedgerRef of(LedgerEntry e) {
            return new LedgerRef(e.seq(), Base64Url.encode(e.prevHash()), Base64Url.encode(e.payloadHash()),
                    e.recordedAt().toEpochMilli(), Base64Url.encode(e.entryHash()));
        }
    }

    /** Everything a reader needs to check the entry and open it; {@code readers[0]} is the owner. */
    record EntryResponse(String entryHash, String owner, String addedAt, String iv, String ciphertext,
                         String signature, List<String> photos, List<ReaderRequest> readers, LedgerRef ledger) {
        static EntryResponse of(ArchiveEntry e) {
            return new EntryResponse(Base64Url.encode(e.entryHash()), Base64Url.encode(e.ownerKey()), e.addedAt(),
                    Base64Url.encode(e.iv()), Base64Url.encode(e.ciphertext()), Base64Url.encode(e.signature()),
                    e.blobHashes().stream().map(Base64Url::encode).toList(),
                    e.readers().stream().map(w -> new ReaderRequest(Base64Url.encode(w.readerKey()),
                            Base64Url.encode(w.ephemeralPublicKey()), Base64Url.encode(w.iv()),
                            Base64Url.encode(w.wrappedKey()))).toList(),
                    LedgerRef.of(e.ledgerEntry()));
        }
    }

    private final ArchiveService archive;

    ArchiveController(ArchiveService archive) {
        this.archive = archive;
    }

    @PostMapping(path = "/api/me/archive/photos", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<Map<String, String>> upload(@AuthenticationPrincipal Jwt jwt, @RequestBody byte[] blob) {
        String hash = Base64Url.encode(archive.upload(UUID.fromString(jwt.getSubject()), blob));
        return ResponseEntity.created(URI.create("/api/me/archive/photos/" + hash)).body(Map.of("hash", hash));
    }

    @PostMapping("/api/me/archive")
    ResponseEntity<EntryResponse> add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddRequest request) {
        ArchiveEntry entry = archive.add(UUID.fromString(jwt.getSubject()), request.addedAt(),
                Base64Url.decode(request.iv()), Base64Url.decode(request.ciphertext()),
                request.photos().stream().map(Base64Url::decode).toList(),
                request.readers().stream().map(r -> new ArchiveService.ReaderWrap(Base64Url.decode(r.publicKey()),
                        Base64Url.decode(r.ephemeralPublicKey()), Base64Url.decode(r.iv()),
                        Base64Url.decode(r.wrappedKey()))).toList(),
                Base64Url.decode(request.signature()));
        String hash = Base64Url.encode(entry.entryHash());
        return ResponseEntity.created(URI.create("/api/me/archive/" + hash)).body(EntryResponse.of(entry));
    }

    /** Entries you can open: your own and those shared with you, newest first. */
    @GetMapping("/api/me/archive")
    List<EntryResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return archive.readable(UUID.fromString(jwt.getSubject())).stream().map(EntryResponse::of).toList();
    }

    @GetMapping("/api/me/archive/{entryHash}")
    EntryResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String entryHash) {
        return EntryResponse.of(archive.entry(UUID.fromString(jwt.getSubject()), hash32(entryHash)));
    }

    /** An encrypted photo; it never changes, so readers' browsers may keep it. */
    @GetMapping(path = "/api/me/archive/photos/{blobHash}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<byte[]> photo(@AuthenticationPrincipal Jwt jwt, @PathVariable String blobHash) {
        byte[] blob = archive.blob(UUID.fromString(jwt.getSubject()), hash32(blobHash));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePrivate().immutable())
                .body(blob);
    }

    static byte[] hash32(String value) {
        byte[] hash = Base64Url.decode(value);
        if (hash.length != 32) {
            throw new IllegalArgumentException("A hash is 32 bytes");
        }
        return hash;
    }
}
