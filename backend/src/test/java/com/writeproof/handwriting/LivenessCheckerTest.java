package com.writeproof.handwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class LivenessCheckerTest {

    private static Set<LivenessFlag> check(HandwritingSample sample) {
        return LivenessChecker.check(sample.validate(), FeatureExtractor.extract(sample));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pen", "touch", "mouse"})
    void genuineHandwritingRaisesNoFlags(String device) {
        for (long w = 1; w <= 50; w++) {
            HandwritingSample sample = new SyntheticSignatures.Writer(w * 7919).genuine(w, device);
            assertThat(check(sample)).as("writer %d on %s", w, device).isEmpty();
        }
    }

    @Test
    void aConstantSpeedScriptIsFlagged() {
        for (long w = 1; w <= 50; w++) {
            HandwritingSample bot = SyntheticSignatures.bot(new SyntheticSignatures.Writer(w * 7919), "mouse");
            assertThat(check(bot)).as("writer %d", w).contains(LivenessFlag.CONSTANT_VELOCITY);
        }
    }

    @Test
    void constantPressureIsFlaggedForPensOnly() {
        SyntheticSignatures.Writer writer = new SyntheticSignatures.Writer(11);

        assertThat(check(SyntheticSignatures.bot(writer, "pen"))).contains(LivenessFlag.CONSTANT_PRESSURE);
        // Mice and touch screens always report 0.5; that is not evidence of anything.
        assertThat(check(writer.genuine(1, "mouse"))).doesNotContain(LivenessFlag.CONSTANT_PRESSURE);
        assertThat(check(writer.genuine(1, "touch"))).doesNotContain(LivenessFlag.CONSTANT_PRESSURE);
    }

    @Test
    void metronomicTimestampsAreFlagged() {
        HandwritingSample sample = SyntheticSignatures.metronome(new SyntheticSignatures.Writer(5), 1);

        assertThat(check(sample)).containsExactly(LivenessFlag.NO_TIMING_VARIANCE);
    }

    @Test
    void aTapOrTinyScribbleIsInsufficient() {
        HandwritingSample tap = FeatureExtractorTest.sample("pen", List.of(
                FeatureExtractorTest.p(10, 10, 0, true),
                FeatureExtractorTest.p(12, 11, 80, true),
                FeatureExtractorTest.p(12, 11, 90, false)));

        assertThat(check(tap)).containsExactly(LivenessFlag.INSUFFICIENT_INPUT);
    }
}
