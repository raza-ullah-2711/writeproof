package com.writeproof.handwriting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class SignatureMatcherTest {

    private static final double THRESHOLD = 0.5;
    private static final int WRITERS = 60;

    private static SignatureMatcher.Template enrol(SyntheticSignatures.Writer writer, long seed, String device) {
        return SignatureMatcher.enrol(LongStream.range(0, 3)
                .mapToObj(i -> FeatureExtractor.extract(writer.genuine(seed * 100 + i, device).validate()))
                .toList());
    }

    private static SignatureMatcher.Match match(SignatureMatcher.Template template, HandwritingSample sample) {
        return SignatureMatcher.match(template, FeatureExtractor.extract(sample.validate()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pen", "mouse"})
    void acceptsTheGenuineWriter(String device) {
        for (long w = 1; w <= WRITERS; w++) {
            SyntheticSignatures.Writer writer = new SyntheticSignatures.Writer(w * 7919);
            SignatureMatcher.Match m = match(enrol(writer, w, device), writer.genuine(w * 100 + 77, device));

            assertThat(m.score()).as("writer %d", w).isGreaterThanOrEqualTo(THRESHOLD);
            assertThat(SignatureMatcher.isReplay(m)).isFalse();
        }
    }

    @Test
    void acceptsTheGenuineWriterAtADifferentSizeAndPosition() {
        SyntheticSignatures.Writer writer = new SyntheticSignatures.Writer(99);
        HandwritingSample bigger = FeatureExtractorTest.transform(writer.genuine(5, "pen"), 1.8, 120, 40);

        assertThat(match(enrol(writer, 1, "pen"), bigger).score()).isGreaterThanOrEqualTo(THRESHOLD);
    }

    @Test
    void rejectsSomeoneElsesSignature() {
        for (long w = 1; w <= WRITERS; w++) {
            SyntheticSignatures.Writer writer = new SyntheticSignatures.Writer(w * 7919);
            double score = match(enrol(writer, w, "pen"), SyntheticSignatures.randomForgery(w * 31, "pen")).score();

            assertThat(score).as("writer %d", w).isLessThan(THRESHOLD);
        }
    }

    @Test
    void rejectsASlowCarefulTracingOfTheRightShape() {
        for (long w = 1; w <= WRITERS; w++) {
            SyntheticSignatures.Writer writer = new SyntheticSignatures.Writer(w * 7919);
            SignatureMatcher.Match m = match(enrol(writer, w, "pen"), SyntheticSignatures.skilledForgery(writer, w * 17, "pen"));

            assertThat(m.score()).as("writer %d", w).isLessThan(THRESHOLD);
            assertThat(m.durationScore()).as("writer %d", w).isLessThan(THRESHOLD);
        }
    }

    @Test
    void rejectsAConstantSpeedScriptOfTheRightShape() {
        for (long w = 1; w <= WRITERS; w++) {
            SyntheticSignatures.Writer writer = new SyntheticSignatures.Writer(w * 7919);
            double score = match(enrol(writer, w, "pen"), SyntheticSignatures.bot(writer, "pen")).score();

            assertThat(score).as("writer %d", w).isLessThan(THRESHOLD);
        }
    }

    @Test
    void detectsAReplayOfAnEnrolledSample() {
        SyntheticSignatures.Writer writer = new SyntheticSignatures.Writer(3);
        HandwritingSample enrolled = writer.genuine(300, "pen");
        SignatureMatcher.Template template = enrol(writer, 3, "pen");

        SignatureMatcher.Match replay = match(template, FeatureExtractorTest.transform(enrolled, 1.1, 5, 5));

        assertThat(SignatureMatcher.isReplay(replay)).isTrue();
    }

    @Test
    void enrolmentNeedsThreeToFiveDistinctSamples() {
        SyntheticSignatures.Writer writer = new SyntheticSignatures.Writer(8);
        Features a = FeatureExtractor.extract(writer.genuine(1, "pen"));
        Features b = FeatureExtractor.extract(writer.genuine(2, "pen"));

        assertThatThrownBy(() -> SignatureMatcher.enrol(List.of(a, b))).hasMessageContaining("3 to 5");
        assertThatThrownBy(() -> SignatureMatcher.enrol(List.of(a, b, a, b, a, b))).hasMessageContaining("3 to 5");
        assertThatThrownBy(() -> SignatureMatcher.enrol(List.of(a, b, a))).hasMessageContaining("copied");
    }
}
