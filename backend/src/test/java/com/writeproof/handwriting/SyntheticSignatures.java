package com.writeproof.handwriting;

import com.writeproof.handwriting.HandwritingSample.StrokePoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;

/**
 * Deterministic synthetic handwriting for tests. A {@link Writer} is a signature: a few cursive
 * strokes plus that person's own speed rhythm and pressure profile. Each call to
 * {@link Writer#genuine} writes it again with natural variation; the forgery generators keep the
 * shape but get the dynamics wrong in the ways real forgers and bots do.
 */
public final class SyntheticSignatures {

    private SyntheticSignatures() {}

    record Curve(double x0, double y0, double length, double[] ax, double[] fx, double[] px,
                 double[] ay, double[] fy, double[] py) {

        double[] at(double u) {
            double x = x0 + length * u, y = y0;
            for (int k = 0; k < ax.length; k++) {
                x += ax[k] * Math.sin(2 * Math.PI * fx[k] * u + px[k]);
                y += ay[k] * Math.sin(2 * Math.PI * fy[k] * u + py[k]);
            }
            return new double[] {x, y};
        }
    }

    public static final class Writer {
        final List<Curve> strokes = new ArrayList<>();
        final double[] strokeMs;       // nominal pen-down duration per stroke
        final double[] gapMs;          // nominal pen-up gap after each stroke
        final double rhythmFreq, rhythmPhase, rhythmDepth;
        final double pressureFreq, pressurePhase;

        public Writer(long seed) {
            Random r = new Random(seed);
            int count = 1 + r.nextInt(3);
            strokeMs = new double[count];
            gapMs = new double[count];
            double x = 40 + r.nextDouble() * 40;
            for (int s = 0; s < count; s++) {
                double length = 120 + r.nextDouble() * 200;
                int harmonics = 3;
                double[] ax = new double[harmonics], fx = new double[harmonics], px = new double[harmonics];
                double[] ay = new double[harmonics], fy = new double[harmonics], py = new double[harmonics];
                for (int k = 0; k < harmonics; k++) {
                    ax[k] = 10 + r.nextDouble() * 30;
                    fx[k] = 1 + r.nextInt(4) + r.nextDouble() * 0.5;
                    px[k] = r.nextDouble() * 2 * Math.PI;
                    ay[k] = 15 + r.nextDouble() * 35;
                    fy[k] = 1 + r.nextInt(5) + r.nextDouble() * 0.5;
                    py[k] = r.nextDouble() * 2 * Math.PI;
                }
                strokes.add(new Curve(x, 100 + r.nextDouble() * 40, length, ax, fx, px, ay, fy, py));
                strokeMs[s] = 500 + r.nextDouble() * 900;
                gapMs[s] = 120 + r.nextDouble() * 250;
                x += length + 20;
            }
            rhythmFreq = 2 + r.nextDouble() * 4;
            rhythmPhase = r.nextDouble() * 2 * Math.PI;
            rhythmDepth = 0.45 + r.nextDouble() * 0.3;
            pressureFreq = 1 + r.nextDouble() * 2;
            pressurePhase = r.nextDouble() * 2 * Math.PI;
        }

        /** The real writer, signing again. */
        public HandwritingSample genuine(long seed, String device) {
            Random r = new Random(seed);
            Variation v = Variation.natural(r);
            return write(this, v, r, device, u -> rhythm(u, 0.15 * r.nextGaussian()), 1.0);
        }

        double rhythm(double u, double phaseJitter) {
            return 1 + rhythmDepth * Math.sin(2 * Math.PI * rhythmFreq * u + rhythmPhase + phaseJitter);
        }
    }

    /** Per-sample deviation from the writer's ideal signature. */
    record Variation(double scale, double rotation, double dx, double dy, double speed,
                     double wobble, double wobbleFreq, double wobblePhase, double tremor, double dtMs, double dtJitter) {

        static Variation natural(Random r) {
            return new Variation(
                    1 + 0.06 * r.nextGaussian(), Math.toRadians(2 * r.nextGaussian()),
                    20 * r.nextGaussian(), 15 * r.nextGaussian(), 1 + 0.08 * r.nextGaussian(),
                    3 + 2 * r.nextDouble(), 1 + r.nextDouble() * 2, r.nextDouble() * 2 * Math.PI,
                    0.3, 1000.0 / 240, 0.35);
        }
    }

    /** The genuine writer, but timestamps come from a fixed-interval timer (exactly 5 ms apart). */
    static HandwritingSample metronome(Writer writer, long seed) {
        Random r = new Random(seed);
        Variation n = Variation.natural(r);
        Variation v = new Variation(n.scale(), n.rotation(), n.dx(), n.dy(), n.speed(), n.wobble(),
                n.wobbleFreq(), n.wobblePhase(), n.tremor(), 5, 0);
        return write(writer, v, r, "mouse", u -> writer.rhythm(u, 0), 1.0);
    }

    /** Someone else's signature entirely. */
    static HandwritingSample randomForgery(long seed, String device) {
        return new Writer(seed ^ 0x5DEECE66DL).genuine(seed, device);
    }

    /**
     * A skilled forger tracing the right shape: careful, therefore slow, with their own (flatter)
     * rhythm and a little tremor.
     */
    public static HandwritingSample skilledForgery(Writer victim, long seed, String device) {
        Random r = new Random(seed);
        Variation natural = Variation.natural(r);
        Variation v = new Variation(natural.scale(), natural.rotation(), natural.dx(), natural.dy(),
                0.4 + 0.1 * r.nextDouble(), natural.wobble(), natural.wobbleFreq(), natural.wobblePhase(),
                1.2, natural.dtMs(), natural.dtJitter());
        double freq = 1 + r.nextDouble();
        return write(victim, v, r, device, u -> 1 + 0.2 * Math.sin(2 * Math.PI * freq * u), 1.0);
    }

    /** A script replaying the shape at perfectly constant speed, constant pressure, exact timing. */
    static HandwritingSample bot(Writer victim, String device) {
        Variation v = new Variation(1, 0, 0, 0, 1, 0, 1, 0, 0, 1000.0 / 240, 0);
        return write(victim, v, new Random(0), device, null, 0);
    }

    /**
     * Writes each stroke by advancing the curve parameter u at a rate set by {@code rhythm}
     * (null means constant speed along the path, as a bot would).
     */
    private static HandwritingSample write(Writer w, Variation v, Random r, String device,
                                           DoubleUnaryOperator rhythm, double pressureVariation) {
        List<List<StrokePoint>> strokes = new ArrayList<>();
        double t = 0;
        double cos = Math.cos(v.rotation()), sin = Math.sin(v.rotation());
        boolean pen = "pen".equals(device);
        for (int s = 0; s < w.strokes.size(); s++) {
            Curve c = w.strokes.get(s);
            double strokeDuration = w.strokeMs[s] / v.speed();
            double[] uAt = rhythm == null ? arcLengthSchedule(c) : null;
            List<StrokePoint> stroke = new ArrayList<>();
            double local = 0;
            while (true) {
                double tau = Math.min(1, local / strokeDuration);
                double u = rhythm == null ? lookup(uAt, tau) : warp(rhythm, tau);
                double[] p = c.at(u);
                double wob = v.wobble() * Math.sin(2 * Math.PI * v.wobbleFreq() * u + v.wobblePhase());
                double x = p[0] + wob + v.tremor() * r.nextGaussian();
                double y = p[1] + wob * 0.7 + v.tremor() * r.nextGaussian();
                double rx = cos * x - sin * y, ry = sin * x + cos * y;
                double pressure = pen
                        ? pressureVariation == 0 ? 0.5
                        : Math.clamp(0.45 + 0.3 * Math.sin(2 * Math.PI * w.pressureFreq * u + w.pressurePhase)
                                + 0.03 * r.nextGaussian(), 0.05, 1)
                        : 0.5;
                boolean done = tau >= 1;
                stroke.add(new StrokePoint(round(rx * v.scale() + v.dx()), round(ry * v.scale() + v.dy()),
                        Math.round((t + local) * 10) / 10.0, done ? 0 : round3(pressure), !done));
                if (done) {
                    break;
                }
                double dt = Math.max(0.5, v.dtMs() + v.dtJitter() * r.nextGaussian());
                local = Math.min(local + dt, strokeDuration);
            }
            strokes.add(stroke);
            t += strokeDuration + w.gapMs[s] * (rhythm == null ? 1 : 1 + 0.15 * r.nextGaussian()) / v.speed();
        }
        return new HandwritingSample(HandwritingSample.FORMAT, 1, "2026-10-02T12:00:00Z", device, 600, 240, strokes);
    }

    /** Maps normalized time to u so that the writer's rhythm sets the instantaneous speed. */
    private static double warp(DoubleUnaryOperator rhythm, double tau) {
        int steps = 200;
        double total = 0, acc = 0;
        for (int i = 0; i < steps; i++) {
            total += rhythm.applyAsDouble((i + 0.5) / steps);
        }
        double target = tau * total;
        for (int i = 0; i < steps; i++) {
            double w = rhythm.applyAsDouble((i + 0.5) / steps);
            if (acc + w >= target) {
                return (i + (target - acc) / w) / steps;
            }
            acc += w;
        }
        return 1;
    }

    /** u values at equal arc-length steps, for constant-speed (bot) drawing. */
    private static double[] arcLengthSchedule(Curve c) {
        int steps = 2000;
        double[] len = new double[steps + 1];
        double[] prev = c.at(0);
        for (int i = 1; i <= steps; i++) {
            double[] p = c.at((double) i / steps);
            len[i] = len[i - 1] + Math.hypot(p[0] - prev[0], p[1] - prev[1]);
            prev = p;
        }
        double[] schedule = new double[steps + 1];
        int j = 0;
        for (int i = 0; i <= steps; i++) {
            double target = len[steps] * i / steps;
            while (j < steps && len[j + 1] < target) {
                j++;
            }
            double seg = len[Math.min(j + 1, steps)] - len[j];
            double frac = seg > 0 ? (target - len[j]) / seg : 0;
            schedule[i] = (j + frac) / steps;
        }
        return schedule;
    }

    private static double lookup(double[] schedule, double tau) {
        double pos = tau * (schedule.length - 1);
        int i = (int) Math.floor(pos);
        if (i >= schedule.length - 1) {
            return schedule[schedule.length - 1];
        }
        return schedule[i] + (pos - i) * (schedule[i + 1] - schedule[i]);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
