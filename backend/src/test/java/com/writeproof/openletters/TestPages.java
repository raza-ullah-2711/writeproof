package com.writeproof.openletters;

import com.writeproof.handwriting.HandwritingSample;
import com.writeproof.handwriting.HandwritingSample.StrokePoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds handwritten pages from synthetic handwriting samples: each sample becomes a line, stacked
 * down the sheet, with one continuous timeline (a pause between lines, as when moving the hand).
 */
public final class TestPages {

    private static final int LINE_HEIGHT = 300;
    private static final long PAUSE_BETWEEN_LINES_MS = 400;

    private TestPages() {}

    public static HandwrittenPage page(Instant capturedAt, String nib, String ink, List<HandwritingSample> lines) {
        List<int[]> strokes = new ArrayList<>();
        List<long[]> timings = new ArrayList<>();
        long offset = 0;
        for (int l = 0; l < lines.size(); l++) {
            HandwritingSample line = lines.get(l);
            double lastT = 0;
            for (List<StrokePoint> stroke : line.strokes()) {
                int[] in = new int[stroke.size() * 3];
                long[] ts = new long[stroke.size()];
                int px = 0, py = 0, pp = 0;
                long pt = 0;
                for (int i = 0; i < stroke.size(); i++) {
                    StrokePoint p = stroke.get(i);
                    // Kept on the sheet, as the browser does: a random sample can stray off its edge.
                    int x = Math.clamp(Math.round(p.x() * 10), 0, HandwrittenPage.WIDTH * 10);
                    int y = Math.clamp(Math.round((p.y() + l * LINE_HEIGHT) * 10), 0, HandwrittenPage.HEIGHT * 10);
                    int pr = (int) Math.round(p.pressure() * 255);
                    long t = offset + Math.round(p.t());
                    in[i * 3] = i == 0 ? x : x - px;
                    in[i * 3 + 1] = i == 0 ? y : y - py;
                    in[i * 3 + 2] = i == 0 ? pr : pr - pp;
                    ts[i] = i == 0 ? t : t - pt;
                    px = x;
                    py = y;
                    pp = pr;
                    pt = t;
                    lastT = p.t();
                }
                strokes.add(in);
                timings.add(ts);
            }
            offset += Math.round(lastT) + PAUSE_BETWEEN_LINES_MS;
        }
        return new HandwrittenPage(HandwrittenPage.FORMAT, 1, capturedAt.toString(), lines.getFirst().device(),
                new HandwrittenPage.Pen(nib, ink), strokes.toArray(int[][]::new), timings.toArray(long[][]::new),
                null);
    }
}
