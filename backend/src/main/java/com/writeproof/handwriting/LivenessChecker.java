package com.writeproof.handwriting;

import com.writeproof.handwriting.HandwritingSample.StrokePoint;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Heuristic checks that a sample was hand-written live, independent of whose handwriting it is.
 * v1 thresholds were calibrated on synthetic strokes (see SyntheticSignatures in the tests) and
 * must be revisited with real data.
 */
public final class LivenessChecker {

    static final double MIN_DURATION_MS = 300;
    static final int MIN_POINTS = 20;
    /** Within-stroke speed CV: synthetic humans >= 0.52, constant-speed scripts <= 0.22. */
    static final double MIN_SPEED_CV = 0.30;
    /** Raw sampling-interval CV below this means a metronome; 240 Hz pens at 0.1 ms resolution sit near 0.01. */
    static final double MIN_INTERVAL_CV = 0.005;
    static final int MIN_INTERVALS = 30;
    static final double MIN_PRESSURE_STD = 0.01;

    private LivenessChecker() {}

    public static Set<LivenessFlag> check(HandwritingSample sample, Features features) {
        Set<LivenessFlag> flags = EnumSet.noneOf(LivenessFlag.class);
        if (features.durationMs() < MIN_DURATION_MS || features.pointCount() < MIN_POINTS) {
            flags.add(LivenessFlag.INSUFFICIENT_INPUT);
            return flags;
        }
        if (Stats.withinStrokeSpeedCv(features) < MIN_SPEED_CV) {
            flags.add(LivenessFlag.CONSTANT_VELOCITY);
        }
        double[] intervals = penDownIntervals(sample);
        if (intervals.length >= MIN_INTERVALS && Stats.cv(intervals) < MIN_INTERVAL_CV) {
            flags.add(LivenessFlag.NO_TIMING_VARIANCE);
        }
        if (features.pen() && Stats.std(features.pressures()) < MIN_PRESSURE_STD) {
            flags.add(LivenessFlag.CONSTANT_PRESSURE);
        }
        return flags;
    }

    /** Time between consecutive in-contact points, within strokes. */
    private static double[] penDownIntervals(HandwritingSample sample) {
        List<Double> intervals = new ArrayList<>();
        for (List<StrokePoint> stroke : sample.strokes()) {
            for (int i = 1; i < stroke.size() - 1; i++) {
                intervals.add(stroke.get(i).t() - stroke.get(i - 1).t());
            }
        }
        return intervals.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
