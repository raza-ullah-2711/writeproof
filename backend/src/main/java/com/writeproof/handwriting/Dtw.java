package com.writeproof.handwriting;

import java.util.Arrays;

/**
 * Dynamic Time Warping between two feature sequences, constrained to a band around the
 * (length-scaled) diagonal. The band keeps the cost near-linear and stops the alignment
 * from matching wildly different timings.
 */
public final class Dtw {

    /** Band half-width as a fraction of the longer sequence. */
    static final double BAND = 0.15;

    private Dtw() {}

    /** Accumulated Euclidean cost along the best warping path, divided by {@code n + m}. */
    public static double distance(double[][] a, double[][] b) {
        int n = a.length, m = b.length;
        if (n == 0 || m == 0) {
            return Double.POSITIVE_INFINITY;
        }
        int radius = Math.max(2, (int) Math.ceil(BAND * Math.max(n, m)));
        double[] prev = new double[m + 1];
        double[] curr = new double[m + 1];
        Arrays.fill(prev, Double.POSITIVE_INFINITY);
        prev[0] = 0;
        for (int i = 1; i <= n; i++) {
            Arrays.fill(curr, Double.POSITIVE_INFINITY);
            int centre = (int) Math.round((double) i * m / n);
            int from = Math.max(1, centre - radius);
            int to = Math.min(m, centre + radius);
            for (int j = from; j <= to; j++) {
                double best = Math.min(prev[j - 1], Math.min(prev[j], curr[j - 1]));
                curr[j] = euclidean(a[i - 1], b[j - 1]) + best;
            }
            double[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[m] / (n + m);
    }

    static double euclidean(double[] u, double[] v) {
        int dims = Math.min(u.length, v.length); // pressure is compared only when both are pens
        double sum = 0;
        for (int d = 0; d < dims; d++) {
            double diff = u[d] - v[d];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }
}
