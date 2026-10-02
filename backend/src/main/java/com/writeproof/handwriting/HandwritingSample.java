package com.writeproof.handwriting;

import java.util.List;

/**
 * Server-side mirror of the frontend {@code writeproof.handwriting} v1 format
 * (see docs/handwriting-capture.md). Stroke dynamics only, never an image.
 */
public record HandwritingSample(
        String format,
        int version,
        String capturedAt,
        String device,
        double width,
        double height,
        List<List<StrokePoint>> strokes) {

    public static final String FORMAT = "writeproof.handwriting";
    public static final int MAX_POINTS = 5_000;

    public record StrokePoint(double x, double y, double t, double pressure, boolean penDown) {}

    public boolean isPen() {
        return "pen".equals(device);
    }

    /** @throws IllegalArgumentException if the sample breaks any rule of the format */
    public HandwritingSample validate() {
        if (!FORMAT.equals(format) || version != 1) {
            throw new IllegalArgumentException("Not a Writeproof handwriting sample (v1)");
        }
        if (!List.of("pen", "touch", "mouse").contains(device)) {
            throw new IllegalArgumentException("Unknown input device");
        }
        if (!(width > 0) || !(height > 0) || strokes == null || strokes.isEmpty()) {
            throw new IllegalArgumentException("Sample has no strokes or invalid dimensions");
        }
        int points = 0;
        double previousT = 0;
        for (List<StrokePoint> stroke : strokes) {
            if (stroke == null || stroke.isEmpty()) {
                throw new IllegalArgumentException("Strokes must be non-empty");
            }
            for (int i = 0; i < stroke.size(); i++) {
                StrokePoint p = stroke.get(i);
                if (p == null || !Double.isFinite(p.x()) || !Double.isFinite(p.y()) || !Double.isFinite(p.t())
                        || !(p.pressure() >= 0 && p.pressure() <= 1)) {
                    throw new IllegalArgumentException("Invalid stroke point");
                }
                if (p.t() < previousT) {
                    throw new IllegalArgumentException("Timestamps must not decrease");
                }
                if (p.penDown() != (i < stroke.size() - 1)) {
                    throw new IllegalArgumentException("Only the last point of a stroke may be pen-up");
                }
                previousT = p.t();
            }
            points += stroke.size();
        }
        if (points > MAX_POINTS) {
            throw new IllegalArgumentException("Sample has more than " + MAX_POINTS + " points");
        }
        return this;
    }
}
