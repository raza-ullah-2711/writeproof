package com.writeproof.handwriting;

final class Stats {

    private Stats() {}

    static double mean(double[] v, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += v[i];
        }
        return to > from ? sum / (to - from) : 0;
    }

    static double std(double[] v, int from, int to) {
        double m = mean(v, from, to);
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += (v[i] - m) * (v[i] - m);
        }
        return to > from ? Math.sqrt(sum / (to - from)) : 0;
    }

    static double mean(double[] v) {
        return mean(v, 0, v.length);
    }

    static double std(double[] v) {
        return std(v, 0, v.length);
    }

    /** Coefficient of variation (std / mean); 0 when the mean is 0. */
    static double cv(double[] v) {
        double m = mean(v);
        return m > 0 ? std(v) / m : 0;
    }

    /**
     * Speed variability inside strokes, pooled over strokes (weighted by frames). Variation
     * between strokes doesn't count: a script can draw each stroke at a different constant speed.
     */
    static double withinStrokeSpeedCv(Features f) {
        double weighted = 0;
        int frames = 0;
        int start = 0;
        for (int count : f.strokeFrames()) {
            int end = start + count;
            if (count >= 5) {
                double m = mean(f.speeds(), start, end);
                if (m > 0) {
                    weighted += std(f.speeds(), start, end) / m * count;
                    frames += count;
                }
            }
            start = end;
        }
        return frames > 0 ? weighted / frames : 0;
    }
}
