package com.writeproof.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.writeproof.calibration.CalibrationEvaluator.Probe;
import com.writeproof.calibration.CalibrationEvaluator.ProbeKind;
import com.writeproof.handwriting.LivenessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CalibrationEvaluatorTest {

    private static Probe probe(ProbeKind kind, double score, LivenessFlag... flags) {
        return new Probe("w", kind, score, flags.length == 0 ? Set.of() : Set.of(flags), "pen", false, false);
    }

    @Test
    void computesErrorRatesExactly() {
        Map<ProbeKind, List<Probe>> probes = Map.of(
                ProbeKind.GENUINE, List.of(probe(ProbeKind.GENUINE, 0.9), probe(ProbeKind.GENUINE, 0.7),
                        probe(ProbeKind.GENUINE, 0.4), probe(ProbeKind.GENUINE, 0.95, LivenessFlag.CONSTANT_VELOCITY)),
                ProbeKind.SKILLED, List.of(probe(ProbeKind.SKILLED, 0.6), probe(ProbeKind.SKILLED, 0.2),
                        probe(ProbeKind.SKILLED, 0.8, LivenessFlag.REPLAY), probe(ProbeKind.SKILLED, 0.1)),
                ProbeKind.RANDOM, List.of(probe(ProbeKind.RANDOM, 0.0), probe(ProbeKind.RANDOM, 0.55)));

        CalibrationEvaluator.Rates at = CalibrationEvaluator.rates(0.5, probes);

        assertThat(at.frr()).isEqualTo(0.25);              // 0.4 rejected on score
        assertThat(at.frrEndToEnd()).isEqualTo(0.5);       // plus the one flagged by liveness
        assertThat(at.skilledFar()).isEqualTo(0.5);        // 0.6 and 0.8
        assertThat(at.skilledFarEndToEnd()).isEqualTo(0.25); // liveness stops the replay
        assertThat(at.randomFar()).isEqualTo(0.5);
        assertThat(at.randomFarEndToEnd()).isEqualTo(0.5);
    }

    @Test
    void boundsFalseAcceptsConservatively() {
        // No forgeries accepted out of 36 still allows ~9.6% at 95% confidence.
        assertThat(CalibrationEvaluator.wilsonUpper(0, 36)).isCloseTo(0.096, within(0.001));
        assertThat(CalibrationEvaluator.wilsonUpper(5, 100)).isCloseTo(0.112, within(0.001));
        assertThat(CalibrationEvaluator.wilsonUpper(0, 0)).isEqualTo(1);
        // Showing FAR <= 1% with a clean run takes about 380 forgery probes.
        assertThat(CalibrationEvaluator.forgeriesNeeded(0.01)).isBetween(375, 385);
    }

    @Test
    void reportsOnASyntheticMultiSessionDataset() throws Exception {
        CalibrationDataset dataset = SyntheticDatasets.build(12, 5, 3, "pen");

        CalibrationEvaluator.Report report = new CalibrationEvaluator(3, 0.5, 0.01).evaluate(dataset);

        assertThat(report.writers()).isEqualTo(12);
        assertThat(report.probes()).containsEntry(ProbeKind.GENUINE, 12 * 7)  // 10 - 3 enrolled
                .containsEntry(ProbeKind.SKILLED, 12 * 3)
                .containsEntry(ProbeKind.RANDOM, 12 * 11);
        assertThat(report.atConfigured().frrEndToEnd()).isLessThanOrEqualTo(0.05);
        assertThat(report.atConfigured().skilledFarEndToEnd()).isZero();
        assertThat(report.atConfigured().randomFarEndToEnd()).isZero();
        assertThat(report.eer()).isLessThan(0.05);
        assertThat(report.eerThreshold()).isBetween(0.2, 0.8); // middle of the zero-error band
        // 168 clean forgery probes bound FAR at ~2.2%: not enough to claim 1%.
        assertThat(report.recommendedThreshold()).isNull();
        assertThat(report.forgeriesNeededForTarget()).isEqualTo(CalibrationEvaluator.forgeriesNeeded(0.01) - 168);
        CalibrationEvaluator.Report lenient = new CalibrationEvaluator(3, 0.5, 0.05).evaluate(dataset);
        assertThat(lenient.recommendedThreshold()).isNotNull();
        assertThat(lenient.atRecommended().farUpperBound()).isLessThanOrEqualTo(0.05);
        assertThat(report.frrCrossSession()).isNotNaN();
        assertThat(report.curve()).hasSize(101);
        // FRR rises and FAR falls as the threshold goes up.
        assertThat(report.curve().get(100).frr()).isGreaterThanOrEqualTo(report.curve().get(0).frr());
        assertThat(report.curve().get(100).skilledFar()).isLessThanOrEqualTo(report.curve().get(0).skilledFar());

        String markdown = CalibrationReportWriter.markdown(report, 0.5);
        assertThat(markdown).contains("# Handwriting calibration report", "Equal error rate", "| Configured | 0.50 |",
                "Collect at least");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/synthetic-baseline.md"), markdown);
    }

    @Test
    void skipsWritersWithTooFewSamples() {
        CalibrationDataset dataset = SyntheticDatasets.build(3, 2, 0, "mouse"); // 4 genuine each: OK
        CalibrationDataset tiny = SyntheticDatasets.build(2, 1, 0, "mouse");    // 2 genuine each: skipped

        assertThat(new CalibrationEvaluator(3, 0.5, 0.01).evaluate(dataset).writers()).isEqualTo(3);
        CalibrationEvaluator.Report skipped = new CalibrationEvaluator(3, 0.5, 0.01).evaluate(tiny);
        assertThat(skipped.writers()).isZero();
        assertThat(skipped.writersSkipped()).isEqualTo(2);
        assertThat(skipped.atConfigured().frr()).isNaN();
        assertThat(skipped.eer()).isNaN();
        assertThat(CalibrationReportWriter.markdown(skipped, 0.5)).contains("Equal error rate: n/a");
    }

    @Test
    void rejectsMalformedDatasets() {
        CalibrationDataset good = SyntheticDatasets.build(1, 1, 1, "pen");
        CalibrationDataset.Entry forgeryWithoutTarget = new CalibrationDataset.Entry("a", "forgery", null,
                "2026-10-01", good.samples().getFirst().sample());

        assertThatThrownBy(() -> new CalibrationDataset("zip", 1, "", List.of()).validate())
                .hasMessageContaining("Not a Writeproof calibration dataset");
        assertThatThrownBy(() -> new CalibrationDataset(CalibrationDataset.FORMAT, 1, "",
                List.of(forgeryWithoutTarget)).validate()).hasMessageContaining("Malformed");
        assertThatThrownBy(() -> new CalibrationEvaluator(2, 0.5, 0.01)).hasMessageContaining("3..5");
    }

    @Test
    void theCommandLineWritesAReport() throws Exception {
        Path file = Files.createTempFile("dataset", ".json");
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(file.toFile(), SyntheticDatasets.build(4, 4, 1, "pen"));

        EvaluateCalibration.main(new String[] {file.toString(), "0.5", "0.01", "3"});

        String report = Files.readString(Path.of(file + ".report.json"));
        assertThat(report).contains("\"writers\" : 4", "\"curve\"");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(report).get("eer").asDouble())
                .isCloseTo(0, within(0.2));
    }
}
