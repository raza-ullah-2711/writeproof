package com.writeproof.handwriting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class DtwTest {

    @Test
    void identicalSequencesHaveZeroDistance() {
        double[][] a = sine(100, 1, 0);
        assertThat(Dtw.distance(a, a)).isZero();
    }

    @Test
    void isSymmetric() {
        double[][] a = sine(100, 1, 0), b = sine(130, 1, 0.3);
        assertThat(Dtw.distance(a, b)).isCloseTo(Dtw.distance(b, a), within(1e-12));
    }

    @Test
    void absorbsTimeWarpingButNotADifferentShape() {
        double[][] base = sine(100, 1, 0);
        double[][] slower = sine(130, 1, 0);      // same path, written over more samples
        double[][] other = sine(100, 3, 0);       // a different path

        double warped = Dtw.distance(base, slower);
        double different = Dtw.distance(base, other);

        assertThat(warped).isLessThan(0.05);
        assertThat(different).isGreaterThan(10 * warped);
    }

    @Test
    void emptySequencesAreInfinitelyFar() {
        assertThat(Dtw.distance(new double[0][], sine(10, 1, 0))).isInfinite();
    }

    /** A 2-D trajectory (t, sin) sampled n times over one pass. */
    private static double[][] sine(int n, double cycles, double phase) {
        double[][] seq = new double[n][];
        for (int i = 0; i < n; i++) {
            double u = (double) i / (n - 1);
            seq[i] = new double[] {u, Math.sin(2 * Math.PI * cycles * u + phase)};
        }
        return seq;
    }
}
