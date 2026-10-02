package com.writeproof.ledger;

/**
 * Where published checkpoints go so that people outside the operator can hold it to them.
 * The default appends to a log file; witnesses (or a cron job pushing that file to a public
 * repository) take it from there. See docs/ledger.md.
 */
public interface CheckpointPublisher {

    void publish(Checkpoint checkpoint);
}
