package com.writeproof.calibration;

import com.writeproof.handwriting.HandwritingSample;
import com.writeproof.handwriting.SyntheticSignatures;
import java.util.ArrayList;
import java.util.List;

/** Builds calibration datasets from the synthetic writer model, for tests and a baseline report. */
final class SyntheticDatasets {

    private SyntheticDatasets() {}

    /**
     * {@code writers} writers, each with {@code perSession} genuine samples in each of two
     * sessions, and {@code forgeriesPerWriter} skilled forgeries of each of them.
     */
    static CalibrationDataset build(int writers, int perSession, int forgeriesPerWriter, String device) {
        List<CalibrationDataset.Entry> entries = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            SyntheticSignatures.Writer writer = new SyntheticSignatures.Writer(7919L * (w + 1));
            String id = "writer-" + w;
            for (String session : List.of("2026-10-01", "2026-10-08")) {
                for (int i = 0; i < perSession; i++) {
                    long seed = (w + 1) * 1000L + session.hashCode() % 97 * 13 + i;
                    entries.add(new CalibrationDataset.Entry(id, "genuine", null, session, writer.genuine(seed, device)));
                }
            }
            for (int f = 0; f < forgeriesPerWriter; f++) {
                HandwritingSample forged = SyntheticSignatures.skilledForgery(writer, (w + 1) * 31L + f, device);
                entries.add(new CalibrationDataset.Entry("forger-" + f, "forgery", id, "2026-10-08", forged));
            }
        }
        return new CalibrationDataset(CalibrationDataset.FORMAT, 1,
                "synthetic (" + writers + " writers, " + device + ")", entries);
    }
}
