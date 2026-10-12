package com.writeproof.openletters;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.writeproof.common.Base64Url;
import com.writeproof.handwriting.HandwritingSample;
import com.writeproof.handwriting.HandwritingSample.StrokePoint;
import com.writeproof.ledger.LedgerHashing;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * A handwritten page ({@code writeproof.page} v1, docs/handwritten-pages.md): stroke dynamics on a
 * 1000 × 1414 sheet, as delta-encoded integers. {@code ink} holds x and y (tenths of a unit) and
 * pressure (0–255) per point; {@code timing} holds milliseconds since the page's first pen-down.
 * The first point of each stroke is absolute, the rest are deltas. Mirrored by the frontend's
 * {@code page-format.ts} and pinned by a shared test vector.
 *
 * <p>The ink and the timing hash separately, so a reader can check the ink against what the author
 * signed while the timing (the writer's rhythm) stays private unless they opted in to replay.
 * {@code timingHash} is output only: the server computes it and never trusts one sent in.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HandwrittenPage(
        String format,
        int version,
        String capturedAt,
        String device,
        Pen pen,
        int[][] ink,
        long[][] timing,
        String timingHash) {

    public static final String FORMAT = "writeproof.page";
    public static final int WIDTH = 1000;
    public static final int HEIGHT = 1414;
    public static final int MAX_POINTS = 25_000;
    public static final int MAX_STROKES = 500;
    public static final List<String> DEVICES = List.of("pen", "touch", "mouse");
    public static final List<String> NIBS = List.of("fountain", "brush", "pencil", "ballpoint");
    public static final List<String> INKS = List.of("ink", "blue-black", "sepia", "seal");

    static final String INK_DOMAIN = "writeproof/page-ink/v1";
    static final String TIMING_DOMAIN = "writeproof/page-timing/v1";
    static final String PAGE_DOMAIN = "writeproof/page/v1";

    public record Pen(String nib, String ink) {}

    /**
     * Checks every rule of the format. A page arriving to be published must carry its timing (the
     * server checks it was written live); a stored one may not.
     *
     * @throws IllegalArgumentException naming the first broken rule
     */
    public HandwrittenPage validate(boolean requireTiming) {
        if (!FORMAT.equals(format) || version != 1) {
            throw new IllegalArgumentException("Not a Writeproof page (v1)");
        }
        if (!DEVICES.contains(device)) {
            throw new IllegalArgumentException("Unknown input device");
        }
        if (pen == null || !NIBS.contains(pen.nib()) || !INKS.contains(pen.ink())) {
            throw new IllegalArgumentException("Unknown pen: nib must be one of " + NIBS + ", ink one of " + INKS);
        }
        if (ink == null || ink.length == 0) {
            throw new IllegalArgumentException("A page needs at least one stroke");
        }
        if (ink.length > MAX_STROKES) {
            throw new IllegalArgumentException("A page can have at most " + MAX_STROKES + " strokes");
        }
        int points = 0;
        for (int[] stroke : ink) {
            if (stroke == null || stroke.length == 0 || stroke.length % 3 != 0) {
                throw new IllegalArgumentException("Each stroke holds x, y and pressure per point");
            }
            int x = 0, y = 0, p = 0;
            for (int i = 0; i < stroke.length; i += 3) {
                x = i == 0 ? stroke[0] : x + stroke[i];
                y = i == 0 ? stroke[1] : y + stroke[i + 1];
                p = i == 0 ? stroke[2] : p + stroke[i + 2];
                if (x < 0 || x > WIDTH * 10 || y < 0 || y > HEIGHT * 10 || p < 0 || p > 255) {
                    throw new IllegalArgumentException("Ink outside the page");
                }
            }
            points += stroke.length / 3;
        }
        if (points > MAX_POINTS) {
            throw new IllegalArgumentException("A page can have at most " + MAX_POINTS + " points");
        }
        if (timing != null || capturedAt != null) {
            validateTiming();
        } else if (requireTiming) {
            throw new IllegalArgumentException("A page must come with its timing to be published");
        }
        return this;
    }

    private void validateTiming() {
        if (capturedAt == null || timing == null || timing.length != ink.length) {
            throw new IllegalArgumentException("Timing must cover every stroke");
        }
        try {
            Instant.parse(capturedAt);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("capturedAt must be an ISO-8601 instant");
        }
        long t = 0;
        for (int s = 0; s < timing.length; s++) {
            long[] stroke = timing[s];
            if (stroke == null || stroke.length != ink[s].length / 3) {
                throw new IllegalArgumentException("Timing must have one entry per point");
            }
            for (int i = 0; i < stroke.length; i++) {
                long next = i == 0 ? stroke[0] : t + stroke[i];
                if (next < t || (s == 0 && i == 0 && next != 0)) {
                    throw new IllegalArgumentException("Times start at 0 and never decrease");
                }
                t = next;
            }
        }
    }

    @JsonIgnore
    public int pointCount() {
        int n = 0;
        for (int[] stroke : ink) {
            n += stroke.length / 3;
        }
        return n;
    }

    @JsonIgnore
    public Instant capturedInstant() {
        return Instant.parse(capturedAt);
    }

    /** SHA-256 over the ink: device, pen and every stroke, exactly as encoded. */
    public byte[] inkHash() {
        StringJoiner strokes = new StringJoiner(";");
        for (int[] stroke : ink) {
            StringJoiner values = new StringJoiner(",");
            for (int v : stroke) {
                values.add(Integer.toString(v));
            }
            strokes.add(values.toString());
        }
        return sha256(INK_DOMAIN + "\n" + device + "\n" + pen.nib() + "\n" + pen.ink() + "\n" + strokes);
    }

    /** SHA-256 over when the page was written and the timing of every point. Needs the timing. */
    public byte[] computeTimingHash() {
        StringJoiner strokes = new StringJoiner(";");
        for (long[] stroke : timing) {
            StringJoiner values = new StringJoiner(",");
            for (long v : stroke) {
                values.add(Long.toString(v));
            }
            strokes.add(values.toString());
        }
        return sha256(TIMING_DOMAIN + "\n" + capturedAt + "\n" + strokes);
    }

    public static byte[] pageHash(byte[] inkHash, byte[] timingHash) {
        return sha256(PAGE_DOMAIN + "\n" + Base64Url.encode(inkHash) + "\n" + Base64Url.encode(timingHash));
    }

    /** The same page without its timing, as published when the author didn't opt in to replay. */
    public HandwrittenPage withoutTiming(byte[] timingHash) {
        return new HandwrittenPage(format, version, null, device, pen, ink, null, Base64Url.encode(timingHash));
    }

    /** The same page with its timing and the timing's hash, as published with replay. */
    public HandwrittenPage withTimingHash(byte[] timingHash) {
        return new HandwrittenPage(format, version, capturedAt, device, pen, ink, timing,
                Base64Url.encode(timingHash));
    }

    /**
     * The page as a handwriting sample in pad units, so the same liveness checks as signatures can
     * run on it. Each stroke's last point is its pen-up.
     */
    public HandwritingSample toSample() {
        List<List<StrokePoint>> strokes = new ArrayList<>(ink.length);
        for (int s = 0; s < ink.length; s++) {
            int[] in = ink[s];
            long[] ts = timing[s];
            List<StrokePoint> stroke = new ArrayList<>(in.length / 3);
            int x = 0, y = 0, p = 0;
            long t = 0;
            for (int i = 0, k = 0; i < in.length; i += 3, k++) {
                x = i == 0 ? in[0] : x + in[i];
                y = i == 0 ? in[1] : y + in[i + 1];
                p = i == 0 ? in[2] : p + in[i + 2];
                t = k == 0 ? ts[0] : t + ts[k];
                stroke.add(new StrokePoint(x / 10.0, y / 10.0, t, p / 255.0, i + 3 < in.length));
            }
            strokes.add(stroke);
        }
        return new HandwritingSample(HandwritingSample.FORMAT, 1, capturedAt, device, WIDTH, HEIGHT, strokes);
    }

    private static byte[] sha256(String text) {
        return LedgerHashing.sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
