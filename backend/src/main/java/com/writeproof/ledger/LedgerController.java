package com.writeproof.ledger;

import com.writeproof.common.Base64Url;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of the ledger so clients can verify the chain themselves. */
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

    private final LedgerService ledger;

    LedgerController(LedgerService ledger) {
        this.ledger = ledger;
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
}
