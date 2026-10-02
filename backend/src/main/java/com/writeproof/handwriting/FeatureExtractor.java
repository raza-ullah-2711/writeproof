package com.writeproof.handwriting;

import com.writeproof.handwriting.HandwritingSample.StrokePoint;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns raw strokes into time-uniform feature sequences. Resampling every {@value #STEP_MS} ms
 * removes differences in device sampling rate (60 Hz mouse vs 240 Hz pen) while keeping the
 * writing's timing: a slower hand produces more frames and smaller velocities.
 */
public final class FeatureExtractor {

    static final double STEP_MS = 10;
    /** Caps DTW cost; longer samples are resampled more coarsely. */
    static final int MAX_FRAMES = 600;
    /** Velocities are tiny in normalized units per step; this weights them against position. */
    static final double VELOCITY_WEIGHT = 8;
    static final double PRESSURE_WEIGHT = 1;

    private FeatureExtractor() {}

    public static Features extract(HandwritingSample sample) {
        List<List<StrokePoint>> strokes = sample.strokes();
        double duration = last(strokes.getLast()).t() - strokes.getFirst().getFirst().t();
        double step = Math.max(STEP_MS, duration / MAX_FRAMES);

        // Resample each stroke's pen-down trajectory (including its closing pen-up point) in time.
        List<double[]> xs = new ArrayList<>();
        List<double[]> ys = new ArrayList<>();
        List<double[]> ps = new ArrayList<>();
        double[] strokeDurations = new double[strokes.size()];
        double[] gaps = new double[strokes.size() - 1];
        int pointCount = 0;
        for (int s = 0; s < strokes.size(); s++) {
            List<StrokePoint> stroke = strokes.get(s);
            pointCount += stroke.size();
            strokeDurations[s] = last(stroke).t() - stroke.getFirst().t();
            if (s > 0) {
                gaps[s - 1] = stroke.getFirst().t() - last(strokes.get(s - 1)).t();
            }
            double[][] resampled = resample(stroke, step);
            xs.add(resampled[0]);
            ys.add(resampled[1]);
            ps.add(resampled[2]);
        }

        // Size and position normalization: centroid to origin, RMS radius to 1. Rotation is kept,
        // since people sign at a consistent angle.
        double cx = 0, cy = 0;
        int n = 0;
        for (int s = 0; s < xs.size(); s++) {
            for (int i = 0; i < xs.get(s).length; i++) {
                cx += xs.get(s)[i];
                cy += ys.get(s)[i];
                n++;
            }
        }
        cx /= n;
        cy /= n;
        double radius = 0;
        for (int s = 0; s < xs.size(); s++) {
            for (int i = 0; i < xs.get(s).length; i++) {
                radius += sq(xs.get(s)[i] - cx) + sq(ys.get(s)[i] - cy);
            }
        }
        radius = Math.sqrt(radius / n);
        double scale = radius > 1e-9 ? 1 / radius : 1;

        boolean pen = sample.isPen();
        int dims = pen ? 5 : 4;
        double[][] frames = new double[n][];
        double[] speeds = new double[n];
        double[] pressures = new double[n];
        int[] strokeFrames = new int[xs.size()];
        int k = 0;
        for (int s = 0; s < xs.size(); s++) {
            double[] x = xs.get(s), y = ys.get(s), p = ps.get(s);
            strokeFrames[s] = x.length;
            for (int i = 0; i < x.length; i++) {
                int a = Math.max(0, i - 1), b = Math.min(x.length - 1, i + 1);
                double span = Math.max(1, b - a);
                double vx = (x[b] - x[a]) / span;
                double vy = (y[b] - y[a]) / span;
                double[] f = new double[dims];
                f[0] = (x[i] - cx) * scale;
                f[1] = (y[i] - cy) * scale;
                f[2] = vx * scale * VELOCITY_WEIGHT;
                f[3] = vy * scale * VELOCITY_WEIGHT;
                if (pen) {
                    f[4] = p[i] * PRESSURE_WEIGHT;
                }
                frames[k] = f;
                speeds[k] = Math.hypot(vx, vy) / step;
                pressures[k] = p[i];
                k++;
            }
        }
        return new Features(frames, speeds, strokeFrames, pressures, strokeDurations, gaps, duration, pointCount, pen);
    }

    /** Linear interpolation of x, y, pressure at fixed time steps across one stroke. */
    private static double[][] resample(List<StrokePoint> stroke, double step) {
        double t0 = stroke.getFirst().t();
        double span = last(stroke).t() - t0;
        int count = (int) Math.floor(span / step) + 1;
        double[] x = new double[count], y = new double[count], p = new double[count];
        int j = 0;
        for (int i = 0; i < count; i++) {
            double t = t0 + i * step;
            while (j < stroke.size() - 2 && stroke.get(j + 1).t() < t) {
                j++;
            }
            StrokePoint a = stroke.get(j);
            StrokePoint b = stroke.get(Math.min(j + 1, stroke.size() - 1));
            double dt = b.t() - a.t();
            double u = dt > 0 ? Math.clamp((t - a.t()) / dt, 0, 1) : 0;
            x[i] = a.x() + u * (b.x() - a.x());
            y[i] = a.y() + u * (b.y() - a.y());
            // The closing pen-up point reports 0 pressure; use the last in-contact pressure instead.
            double pb = b.penDown() ? b.pressure() : a.pressure();
            p[i] = a.pressure() + u * (pb - a.pressure());
        }
        return new double[][] {x, y, p};
    }

    private static StrokePoint last(List<StrokePoint> stroke) {
        return stroke.getLast();
    }

    private static double sq(double v) {
        return v * v;
    }
}
