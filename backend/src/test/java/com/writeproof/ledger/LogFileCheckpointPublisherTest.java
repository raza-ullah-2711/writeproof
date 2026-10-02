package com.writeproof.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogFileCheckpointPublisherTest {

    @Test
    void appendsOneJsonLinePerCheckpoint(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("checkpoints.jsonl");
        var publisher = new LogFileCheckpointPublisher(new LedgerProperties("unused", null, log.toString()));

        publisher.publish(new Checkpoint(1, new byte[32], 1000, new byte[64]));
        publisher.publish(new Checkpoint(2, new byte[32], 2000, new byte[64]));

        assertThat(Files.readAllLines(log)).hasSize(2).first().asString()
                .startsWith("{\"size\":1,\"root\":\"AAAA").contains("\"timestampMillis\":1000");
    }
}
