package com.writeproof.handwriting;

/**
 * What the matcher and liveness checks work on, extracted from one sample.
 *
 * @param frames        per-step feature vectors for DTW: normalized x, y, velocity (and pressure for pens)
 * @param speeds        raw pen speed per resampled step, in pad units per ms
 * @param strokeFrames  how many consecutive frames (and speeds) belong to each stroke
 * @param pressures     raw pressure per resampled step
 * @param strokeDurations pen-down time of each stroke, ms
 * @param penUpGaps     time between consecutive strokes, ms
 * @param durationMs    first pen-down to last pen-up
 * @param pen           whether pressure is real (pen input)
 */
public record Features(
        double[][] frames,
        double[] speeds,
        int[] strokeFrames,
        double[] pressures,
        double[] strokeDurations,
        double[] penUpGaps,
        double durationMs,
        int pointCount,
        boolean pen) {}
