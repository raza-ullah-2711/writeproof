package com.writeproof.ledger;

import com.writeproof.common.Base64Url;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lets clients and outside auditors verify the ledger themselves. The key, checkpoints and proofs
 * are public: they reveal only hashes and sizes. Reading raw entries needs a login.
 */
@RestController
@RequestMapping("/api/ledger")
class LedgerController {

    static final int MAX_PAGE = 1000;

    record EntryResponse(long seq, String prevHash, String payloadHash, long recordedAtMillis, Instant recordedAt,
                         String entryHash) {
        static EntryResponse of(LedgerEntry e) {
            return new EntryResponse(e.seq(), Base64Url.encode(e.prevHash()), Base64Url.encode(e.payloadHash()),
                    e.recordedAt().toEpochMilli(), e.recordedAt(), Base64Url.encode(e.entryHash()));
        }
    }

    /** The current key and every rotation that led to it, oldest first. */
    record KeyResponse(String publicKey, List<RotationResponse> rotations) {}

    record RotationResponse(String oldKey, String newKey, long size, String root, long timestampMillis,
                            String signature) {
        static RotationResponse of(KeyRotation r) {
            return new RotationResponse(Base64Url.encode(r.oldKey()), Base64Url.encode(r.newKey()), r.size(),
                    Base64Url.encode(r.root()), r.timestampMillis(), Base64Url.encode(r.signature()));
        }
    }

    record CheckpointResponse(long size, String root, long timestampMillis, String signature) {
        static CheckpointResponse of(Checkpoint c) {
            return new CheckpointResponse(c.size(), Base64Url.encode(c.root()), c.timestampMillis(),
                    Base64Url.encode(c.signature()));
        }
    }

    record InclusionResponse(CheckpointResponse checkpoint, EntryResponse entry, List<String> proof) {}

    record ConsistencyResponse(long from, long to, List<String> proof) {}

    private final LedgerService ledger;
    private final CheckpointService checkpoints;
    private final LedgerSigner signer;
    private final KeyRotationService rotations;

    LedgerController(LedgerService ledger, CheckpointService checkpoints, LedgerSigner signer,
                     KeyRotationService rotations) {
        this.ledger = ledger;
        this.checkpoints = checkpoints;
        this.signer = signer;
        this.rotations = rotations;
    }

    @GetMapping("/entries")
    List<EntryResponse> entries(@RequestParam(defaultValue = "1") long from,
                                @RequestParam(defaultValue = "" + MAX_PAGE) int limit) {
        return ledger.entries(Math.max(1, from), Math.clamp(limit, 1, MAX_PAGE)).stream()
                .map(EntryResponse::of)
                .toList();
    }

    @GetMapping("/verify")
    LedgerService.ChainCheck verify() {
        return ledger.verify();
    }

    @GetMapping("/key")
    KeyResponse key() {
        return new KeyResponse(Base64Url.encode(signer.publicKey()),
                rotations.rotations().stream().map(RotationResponse::of).toList());
    }

    @GetMapping("/checkpoint")
    CheckpointResponse checkpoint() {
        return CheckpointResponse.of(checkpoints.current());
    }

    @GetMapping("/checkpoints")
    List<CheckpointResponse> published(@RequestParam(defaultValue = "0") long after,
                                       @RequestParam(defaultValue = "100") int limit) {
        return checkpoints.published(Math.max(0, after), Math.clamp(limit, 1, MAX_PAGE)).stream()
                .map(CheckpointResponse::of).toList();
    }

    /** An entry, a fresh signed checkpoint covering it, and the proof that it is in that checkpoint. */
    @GetMapping("/proof/inclusion")
    InclusionResponse inclusion(@RequestParam long seq) {
        Checkpoint checkpoint = checkpoints.current();
        if (seq < 1 || seq > checkpoint.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such ledger entry");
        }
        LedgerEntry entry = ledger.entry(seq).orElseThrow();
        return new InclusionResponse(CheckpointResponse.of(checkpoint), EntryResponse.of(entry),
                encode(ledger.inclusionProof(seq, checkpoint.size())));
    }

    /** Proof that the ledger at {@code to} entries extends the ledger at {@code from} unchanged. */
    @GetMapping("/proof/consistency")
    ConsistencyResponse consistency(@RequestParam long from, @RequestParam long to) {
        if (from < 1 || from > to || to > ledger.size()) {
            throw new IllegalArgumentException("Need 1 <= from <= to <= current size");
        }
        return new ConsistencyResponse(from, to, encode(ledger.consistencyProof(from, to)));
    }

    private static List<String> encode(List<byte[]> hashes) {
        return hashes.stream().map(Base64Url::encode).toList();
    }
}
