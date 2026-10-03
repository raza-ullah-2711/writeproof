package com.writeproof.ledger;

import com.writeproof.common.Base64Url;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
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

    /** Where a checkpoint is anchored in the public log: look it up there, not here (docs/ledger.md). */
    record AnchorResponse(long size, String logUrl, long logIndex, String uuid, long integratedTime) {
        static AnchorResponse of(AnchorService.Anchor a) {
            return new AnchorResponse(a.size(), a.logUrl(), a.logIndex(), a.uuid(), a.integratedTime());
        }
    }

    private final LedgerService ledger;
    private final CheckpointService checkpoints;
    private final LedgerSigner signer;
    private final KeyRotationService rotations;
    private final AnchorService anchors;

    LedgerController(LedgerService ledger, CheckpointService checkpoints, LedgerSigner signer,
                     KeyRotationService rotations, AnchorService anchors) {
        this.ledger = ledger;
        this.checkpoints = checkpoints;
        this.signer = signer;
        this.rotations = rotations;
        this.anchors = anchors;
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

    record EntryProof(long logIndex, long treeSize, String rootHash, List<String> hashes, String checkpoint) {}

    /** A log entry as the log returned it: hashes in hex and the body in base64, like Rekor's own API. */
    record LogEntryResponse(String uuid, long logIndex, String body, EntryProof proof) {}

    record AnchorProofResponse(CheckpointResponse checkpoint, String logUrl, LogEntryResponse entry) {}

    /** The latest anchor with its log entry, relayed from the log for browsers to verify themselves. */
    @GetMapping("/anchor/latest")
    AnchorProofResponse latestAnchor() {
        AnchorService.AnchorProof p;
        try {
            p = anchors.latestProof()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nothing anchored yet"));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The public log is unreachable");
        }
        Rekor.InclusionProof proof = p.entry().proof();
        HexFormat hex = HexFormat.of();
        return new AnchorProofResponse(CheckpointResponse.of(p.checkpoint()), p.anchor().logUrl(),
                new LogEntryResponse(p.entry().uuid(), p.entry().logIndex(),
                        Base64.getEncoder().encodeToString(p.entry().body()),
                        new EntryProof(proof.logIndex(), proof.treeSize(), hex.formatHex(proof.rootHash()),
                                proof.hashes().stream().map(hex::formatHex).toList(), proof.checkpoint())));
    }

    @GetMapping("/anchors")
    List<AnchorResponse> anchors(@RequestParam(defaultValue = "0") long after,
                                 @RequestParam(defaultValue = "100") int limit) {
        return anchors.anchors(Math.max(0, after), Math.clamp(limit, 1, MAX_PAGE)).stream()
                .map(AnchorResponse::of).toList();
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
