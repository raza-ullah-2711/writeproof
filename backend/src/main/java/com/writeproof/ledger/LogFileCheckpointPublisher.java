package com.writeproof.ledger;

import com.writeproof.common.Base64Url;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Logs each checkpoint, and appends it as a JSON line to {@code writeproof.ledger.checkpoint-log} if set. */
@Component
class LogFileCheckpointPublisher implements CheckpointPublisher {

    private static final Logger log = LoggerFactory.getLogger(LogFileCheckpointPublisher.class);

    private final LedgerProperties properties;

    LogFileCheckpointPublisher(LedgerProperties properties) {
        this.properties = properties;
    }

    @Override
    public void publish(Checkpoint c) {
        String line = String.format("{\"size\":%d,\"root\":\"%s\",\"timestampMillis\":%d,\"signature\":\"%s\"}",
                c.size(), Base64Url.encode(c.root()), c.timestampMillis(), Base64Url.encode(c.signature()));
        log.info("Ledger checkpoint {}", line);
        if (properties.checkpointLog() != null && !properties.checkpointLog().isBlank()) {
            try {
                Files.writeString(Path.of(properties.checkpointLog()), line + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.error("Could not append checkpoint to {}", properties.checkpointLog(), e);
            }
        }
    }
}
