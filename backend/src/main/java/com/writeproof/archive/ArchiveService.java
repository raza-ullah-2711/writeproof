package com.writeproof.archive;

import com.writeproof.common.Base64Url;
import com.writeproof.identity.Account;
import com.writeproof.identity.AccountRepository;
import com.writeproof.identity.AccountStatus;
import com.writeproof.identity.Ed25519;
import com.writeproof.ledger.LedgerEntry;
import com.writeproof.ledger.LedgerHashing;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The private family archive (docs/family-archive.md): photos of paper letters sealed end to end
 * to the people the owner chooses, signed and recorded on the ledger. The server checks sizes,
 * who may read what, and the owner's signature; it can't see the photos or their description.
 */
@Service
public class ArchiveService {

    /** An encrypted photo: up to 1.5 MiB of JPEG, plus its 12-byte IV and 16-byte tag. */
    public static final int MAX_BLOB = 1536 * 1024 + 28;
    public static final int MAX_PHOTOS = 10;
    /** The owner plus up to 20 people it's shared with. */
    public static final int MAX_READERS = 21;
    static final int MAX_PENDING_BLOBS = 30;
    static final long MAX_STORED_BYTES = 500L * 1024 * 1024;
    /** Uploads not attached to an entry within this long are deleted. */
    static final Duration ABANDONED_AFTER = Duration.ofDays(1);

    private static final Logger log = LoggerFactory.getLogger(ArchiveService.class);

    private final ArchiveRepository archive;
    private final AccountRepository accounts;
    private final AccountStatus accountStatus;
    private final LedgerService ledger;
    private final SystemSettings settings;
    private final Clock clock;

    ArchiveService(ArchiveRepository archive, AccountRepository accounts, AccountStatus accountStatus,
                   LedgerService ledger, SystemSettings settings, Clock clock) {
        this.archive = archive;
        this.accounts = accounts;
        this.accountStatus = accountStatus;
        this.ledger = ledger;
        this.settings = settings;
        this.clock = clock;
    }

    /** Stores one encrypted photo until an entry signs it; returns its hash. */
    @Transactional
    public byte[] upload(UUID ownerId, byte[] blob) {
        settings.requireSendingEnabled();
        accountStatus.requireCanSend(ownerId);
        if (blob == null || blob.length <= 28 || blob.length > MAX_BLOB) {
            throw new IllegalArgumentException("An encrypted photo must be 29 to " + MAX_BLOB + " bytes");
        }
        if (archive.pendingBlobs(ownerId) >= MAX_PENDING_BLOBS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many photos waiting to be added; add them to an entry first");
        }
        if (archive.storedBytes(ownerId) + blob.length > MAX_STORED_BYTES) {
            throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,
                    "Your archive is full (" + MAX_STORED_BYTES / (1024 * 1024) + " MB)");
        }
        byte[] hash = LedgerHashing.sha256(blob);
        if (!archive.insertBlob(hash, ownerId, blob, clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This photo was already uploaded");
        }
        return hash;
    }

    /** A reader's wrapped key as sent: whose it is (their address) and the wrap. */
    public record ReaderWrap(byte[] publicKey, byte[] ephemeralPublicKey, byte[] iv, byte[] wrappedKey) {}

    /**
     * Adds an entry. {@code readers} starts with the owner; every reader must have an encryption
     * key. The owner's wallet signs the entry hash, which covers the photos, the encrypted
     * description and every wrapped key.
     */
    @Transactional
    public ArchiveEntry add(UUID ownerId, String addedAt, byte[] iv, byte[] ciphertext, List<byte[]> blobHashes,
                            List<ReaderWrap> readers, byte[] signature) {
        settings.requireSendingEnabled();
        Instant now = clock.instant();
        LetterService.requireCurrent(addedAt, now);
        if (iv == null || iv.length != 12 || ciphertext == null || ciphertext.length <= 16
                || ciphertext.length > 64 * 1024 + 16) {
            throw new IllegalArgumentException("The description must be 17 to 65552 bytes of ciphertext");
        }
        if (blobHashes.isEmpty() || blobHashes.size() > MAX_PHOTOS) {
            throw new IllegalArgumentException("An entry needs 1 to " + MAX_PHOTOS + " photos");
        }
        if (readers.isEmpty() || readers.size() > MAX_READERS) {
            throw new IllegalArgumentException("An entry is shared with at most " + (MAX_READERS - 1) + " people");
        }
        Account owner = accounts.findById(ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
        accountStatus.requireCanSend(ownerId);
        if (!java.util.Arrays.equals(readers.getFirst().publicKey(), owner.publicKey())) {
            throw new IllegalArgumentException("The owner must be the first reader");
        }

        List<UUID> readerIds = new ArrayList<>();
        List<ArchiveHashing.Wrap> wraps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ReaderWrap r : readers) {
            if (!seen.add(Base64Url.encode(r.publicKey()))) {
                throw new IllegalArgumentException("Each reader is listed once");
            }
            requireLength(r.ephemeralPublicKey(), 32, "ephemeralPublicKey");
            requireLength(r.iv(), 12, "iv");
            requireLength(r.wrappedKey(), 48, "wrappedKey");
            Account reader = accounts.findByPublicKey(r.publicKey())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No account with this address"));
            if (!reader.canReceiveLetters()) {
                throw unprocessable("Everyone it's shared with needs a registered encryption key");
            }
            readerIds.add(reader.id());
            wraps.add(new ArchiveHashing.Wrap(r.publicKey(), r.ephemeralPublicKey(), r.iv(), r.wrappedKey()));
        }
        Set<String> distinctBlobs = new HashSet<>();
        for (byte[] h : blobHashes) {
            requireLength(h, 32, "photo hash");
            if (!distinctBlobs.add(Base64Url.encode(h))) {
                throw new IllegalArgumentException("Each photo is listed once");
            }
        }

        String header = ArchiveHashing.header(owner.publicKey(), addedAt, blobHashes);
        byte[] hash = ArchiveHashing.entryHash(header, iv, ciphertext, wraps);
        if (!Ed25519.verify(owner.publicKey(), LetterHashing.signedMessage(hash), signature)) {
            throw unprocessable("Signature does not verify against the owner's key");
        }
        if (archive.exists(hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This entry was already added");
        }
        if (!archive.ownsPending(ownerId, blobHashes)) {
            throw unprocessable("Upload each photo first; a photo belongs to one entry only");
        }

        LedgerEntry entry = ledger.append(hash);
        archive.insert(hash, ownerId, addedAt, iv, ciphertext, signature, entry.seq(), now, readerIds, wraps,
                blobHashes);
        return new ArchiveEntry(hash, owner.publicKey(), addedAt, iv, ciphertext, signature, entry, blobHashes,
                wraps);
    }

    public List<ArchiveEntry> readable(UUID accountId) {
        return archive.forReader(accountId);
    }

    public ArchiveEntry entry(UUID accountId, byte[] entryHash) {
        // Unknown and not-yours look the same, so this can't probe for other people's entries.
        return archive.forReader(accountId, entryHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such entry"));
    }

    public byte[] blob(UUID accountId, byte[] blobHash) {
        return archive.blobFor(accountId, blobHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such photo"));
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    public void deleteAbandonedUploads() {
        int n = archive.deleteAbandoned(clock.instant().minus(ABANDONED_AFTER));
        if (n > 0) {
            log.info("Deleted {} abandoned archive uploads", n);
        }
    }

    private static void requireLength(byte[] value, int length, String name) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException(name + " must be " + length + " bytes");
        }
    }

    private static ResponseStatusException unprocessable(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }
}
