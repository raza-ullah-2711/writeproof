package com.writeproof.handwriting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.writeproof.handwriting.HandwritingSample.StrokePoint;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeatureExtractorTest {

    @Test
    void resamplesEachStrokeEvery10msAndKeepsStrokeTiming() {
        HandwritingSample sample = sample("pen",
                List.of(p(0, 0, 0, true), p(100, 0, 100, true), p(100, 0, 105, false)),
                List.of(p(0, 50, 305, true), p(0, 100, 355, true), p(0, 100, 360, false)));

        Features f = FeatureExtractor.extract(sample.validate());

        assertThat(f.strokeFrames()).containsExactly(11, 6); // 0..100 ms, 0..50 ms in 10 ms steps
        assertThat(f.durationMs()).isEqualTo(360);
        assertThat(f.strokeDurations()).containsExactly(105, 55);
        assertThat(f.penUpGaps()).containsExactly(200);
        assertThat(f.speeds()[5]).isCloseTo(1.0, within(1e-9)); // 100 px in 100 ms
    }

    @Test
    void isInvariantToPositionAndSize() {
        SyntheticSignatures.Writer writer = new SyntheticSignatures.Writer(42);
        HandwritingSample original = writer.genuine(1, "pen");
        HandwritingSample movedAndScaled = transform(original, 2.5, 80, -30);

        double[][] a = FeatureExtractor.extract(original.validate()).frames();
        double[][] b = FeatureExtractor.extract(movedAndScaled.validate()).frames();

        assertThat(b.length).isEqualTo(a.length);
        assertThat(Dtw.distance(a, b)).isLessThan(1e-3);
    }

    @Test
    void includesPressureOnlyForPens() {
        SyntheticSignatures.Writer writer = new SyntheticSignatures.Writer(7);

        assertThat(FeatureExtractor.extract(writer.genuine(1, "pen")).frames()[0]).hasSize(5);
        assertThat(FeatureExtractor.extract(writer.genuine(1, "mouse")).frames()[0]).hasSize(4);
    }

    @Test
    void capsTheNumberOfFramesForLongSamples() {
        HandwritingSample slow = sample("mouse", List.of(p(0, 0, 0, true), p(10, 10, 60_000, true), p(10, 10, 60_001, false)));

        assertThat(FeatureExtractor.extract(slow.validate()).frames().length)
                .isLessThanOrEqualTo(FeatureExtractor.MAX_FRAMES + 1);
    }

    static HandwritingSample transform(HandwritingSample s, double scale, double dx, double dy) {
        return new HandwritingSample(s.format(), s.version(), s.capturedAt(), s.device(), s.width(), s.height(),
                s.strokes().stream().map(stroke -> stroke.stream()
                        .map(q -> new StrokePoint(q.x() * scale + dx, q.y() * scale + dy, q.t(), q.pressure(), q.penDown()))
                        .toList()).toList());
    }

    @SafeVarargs
    static HandwritingSample sample(String device, List<StrokePoint>... strokes) {
        return new HandwritingSample(HandwritingSample.FORMAT, 1, "2026-10-02T12:00:00Z", device, 600, 240, List.of(strokes));
    }

    static StrokePoint p(double x, double y, double t, boolean penDown) {
        return new StrokePoint(x, y, t, penDown ? 0.5 : 0, penDown);
    }
}
