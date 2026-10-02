package com.writeproof.calibration;

import com.writeproof.handwriting.HandwritingSample;
import java.util.List;

/**
 * A labelled handwriting dataset ({@code writeproof.calibration-dataset} v1): what the exporter
 * writes and the evaluator reads. Writers are pseudonymous ids. {@code session} is the UTC day a
 * sample was written; enrolment uses the earliest session, so later sessions measure drift.
 */
public record CalibrationDataset(String format, int version, String source, List<Entry> samples) {

    public static final String FORMAT = "writeproof.calibration-dataset";

    /** @param target for forgeries, the writer whose practice name was imitated; else null */
    public record Entry(String writer, String kind, String target, String session, HandwritingSample sample) {

        boolean genuine() {
            return "genuine".equals(kind);
        }
    }

    public CalibrationDataset validate() {
        if (!FORMAT.equals(format) || version != 1 || samples == null) {
            throw new IllegalArgumentException("Not a Writeproof calibration dataset (v1)");
        }
        for (Entry e : samples) {
            if (e.writer() == null || e.session() == null || e.sample() == null
                    || !("genuine".equals(e.kind()) || "forgery".equals(e.kind()))
                    || ("forgery".equals(e.kind()) == (e.target() == null))) {
                throw new IllegalArgumentException("Malformed dataset entry for writer " + e.writer());
            }
            e.sample().validate();
        }
        return this;
    }
}
