package com.writeproof.handwriting;

/** Reasons a sample does not look like a live human writing it just now. */
public enum LivenessFlag {
    /** Too short or too few points to judge. */
    INSUFFICIENT_INPUT,
    /** Speed barely changes within strokes; hands accelerate and brake, scripts don't. */
    CONSTANT_VELOCITY,
    /** Sampling intervals are perfectly identical, as from a fixed-interval timer. */
    NO_TIMING_VARIANCE,
    /** A pen that reports the same pressure throughout. Not checked for mouse or touch. */
    CONSTANT_PRESSURE,
    /**
     * Near-identical to an enrolled sample or to a recent letter signature; nobody writes the
     * same thing twice exactly.
     */
    REPLAY,
    /** Captured too long ago (or in the future) to count as signing this letter now. */
    STALE
}
