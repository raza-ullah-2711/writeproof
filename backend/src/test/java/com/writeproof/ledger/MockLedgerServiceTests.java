package com.writeproof.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.writeproof.TestcontainersConfiguration;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockLedgerServiceTests {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcClient jdbc;

    private final SecureRandom random = new SecureRandom();

    private byte[] randomHash() {
        byte[] hash = new byte[32];
        random.nextBytes(hash);
        return hash;
    }

    private LedgerEntry append(byte[] payload) {
        return tx.execute(status -> ledger.append(payload));
    }

    @Test
    void eachEntryLinksToThePreviousOne() {
        LedgerEntry first = append(randomHash());
        LedgerEntry second = append(randomHash());

        assertThat(second.seq()).isEqualTo(first.seq() + 1);
        assertThat(second.prevHash()).isEqualTo(first.entryHash());
        assertThat(second.entryHash()).isEqualTo(LedgerHashing.entryHash(
                second.seq(), second.prevHash(), second.payloadHash(), second.recordedAt()));
        assertThat(ledger.entry(second.seq()).orElseThrow().entryHash()).isEqualTo(second.entryHash());
        assertThat(ledger.verify().intact()).isTrue();
    }

    @Test
    void theFirstEntryLinksToTheGenesisHash() {
        append(randomHash());

        assertThat(ledger.entry(1).orElseThrow().prevHash()).isEqualTo(LedgerHashing.GENESIS_PREV_HASH);
    }

    @Test
    void entriesCannotBeUpdatedDeletedOrTruncated() {
        LedgerEntry entry = append(randomHash());

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_entries SET payload_hash = :p WHERE seq = :seq")
                .param("p", randomHash()).param("seq", entry.seq()).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_entries WHERE seq = :seq").param("seq", entry.seq()).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE ledger_entries CASCADE").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void verificationFindsATamperedEntry() {
        LedgerEntry entry = append(randomHash());
        append(randomHash());
        byte[] original = entry.payloadHash();
        // Simulate someone with raw database access bypassing the append-only trigger.
        jdbc.sql("ALTER TABLE ledger_entries DISABLE TRIGGER ledger_entries_append_only").update();
        try {
            jdbc.sql("UPDATE ledger_entries SET payload_hash = :p WHERE seq = :seq")
                    .param("p", randomHash()).param("seq", entry.seq()).update();

            LedgerService.ChainCheck check = ledger.verify();

            assertThat(check.intact()).isFalse();
            assertThat(check.brokenAt()).isEqualTo(entry.seq());
        } finally {
            jdbc.sql("UPDATE ledger_entries SET payload_hash = :p WHERE seq = :seq")
                    .param("p", original).param("seq", entry.seq()).update();
            jdbc.sql("ALTER TABLE ledger_entries ENABLE TRIGGER ledger_entries_append_only").update();
        }
        assertThat(ledger.verify().intact()).isTrue();
    }

    @Test
    void concurrentAppendsNeverForkTheChain() throws Exception {
        long before = ledger.head().map(LedgerEntry::seq).orElse(0L);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<LedgerEntry>> jobs = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                jobs.add(() -> append(randomHash()));
            }
            List<Long> seqs = new ArrayList<>();
            for (Future<LedgerEntry> f : pool.invokeAll(jobs)) {
                seqs.add(f.get().seq());
            }
            assertThat(seqs).doesNotHaveDuplicates().hasSize(40);
        } finally {
            pool.shutdown();
        }
        assertThat(ledger.head().orElseThrow().seq()).isEqualTo(before + 40);
        assertThat(ledger.verify().intact()).isTrue();
    }

    @Test
    void appendsMustJoinACallersTransaction() {
        assertThatThrownBy(() -> ledger.append(randomHash())).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void rejectsPayloadsThatAreNotHashes() {
        assertThatThrownBy(() -> append(Arrays.copyOf(randomHash(), 31))).isInstanceOf(IllegalArgumentException.class);
    }
}
