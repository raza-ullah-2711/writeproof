package com.writeproof.calibration;

import java.util.Locale;
import java.util.Map;

/** Renders an evaluation report as Markdown for humans. */
public final class CalibrationReportWriter {

    private CalibrationReportWriter() {}

    public static String markdown(CalibrationEvaluator.Report r, double configuredThreshold) {
        StringBuilder md = new StringBuilder();
        md.append("# Handwriting calibration report\n\n");
        md.append("Source: ").append(r.source()).append("  \n");
        md.append(String.format(Locale.ROOT, "Writers evaluated: %d (skipped %d with too few samples)  %n",
                r.writers(), r.writersSkipped()));
        md.append(String.format(Locale.ROOT, "Probes: %d genuine, %d skilled forgeries, %d random forgeries%n%n",
                r.probes().get(CalibrationEvaluator.ProbeKind.GENUINE),
                r.probes().get(CalibrationEvaluator.ProbeKind.SKILLED),
                r.probes().get(CalibrationEvaluator.ProbeKind.RANDOM)));

        md.append("## Summary\n\n| | Threshold | FRR (match) | FRR (end to end) | Skilled FAR (e2e) | Random FAR (e2e) |\n");
        md.append("| --- | --- | --- | --- | --- | --- |\n");
        row(md, "Configured", r.atConfigured());
        if (r.atRecommended() != null) {
            row(md, String.format(Locale.ROOT, "Recommended (FAR <= %s)", pct(r.targetFar())), r.atRecommended());
        } else {
            md.append(String.format(Locale.ROOT, "| Recommended | none proven at FAR <= %s | | | | |%n", pct(r.targetFar())));
        }
        md.append(String.format(Locale.ROOT, "%nA threshold is recommended only when the 95%% upper bound on its false-accept "
                + "rate (skilled + random forgeries, end to end) meets the target.%n"));
        if (r.forgeriesNeededForTarget() > 0) {
            md.append(String.format(Locale.ROOT, "**Collect at least %d more forgery probes** to be able to show FAR <= %s.%n",
                    r.forgeriesNeededForTarget(), pct(r.targetFar())));
        }
        if (Double.isNaN(r.eer())) {
            md.append("\nEqual error rate: n/a (no genuine or forgery probes).\n\n");
        } else {
            md.append(String.format(Locale.ROOT, "%nEqual error rate (match only): **%s** at threshold %.2f.%n%n",
                    pct(r.eer()), r.eerThreshold()));
        }

        md.append(String.format(Locale.ROOT,
                "## Genuine rejections at the configured threshold (%.2f)%n%n", configuredThreshold));
        md.append(String.format(Locale.ROOT, "- Later sessions than enrolment: %s%n", pct(r.frrCrossSession())));
        md.append(String.format(Locale.ROOT, "- Different device than enrolment: %s%n", pct(r.frrCrossDevice())));
        for (Map.Entry<String, Double> e : r.frrByProbeDevice().entrySet()) {
            md.append(String.format(Locale.ROOT, "- Probes written with %s: %s%n", e.getKey(), pct(e.getValue())));
        }
        md.append("\nLiveness flags raised on genuine samples:");
        if (r.livenessFalseRejects().isEmpty()) {
            md.append(" none.\n");
        } else {
            md.append("\n\n");
            r.livenessFalseRejects().forEach((flag, rate) ->
                    md.append(String.format(Locale.ROOT, "- `%s`: %s%n", flag, pct(rate))));
        }

        md.append("\n## Score distributions (min, deciles, max)\n\n");
        r.scoreDeciles().forEach((kind, d) -> {
            md.append("- ").append(kind.name().toLowerCase(Locale.ROOT)).append(": ");
            StringBuilder values = new StringBuilder();
            for (double v : d) {
                values.append(values.isEmpty() ? "" : " ").append(Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.2f", v));
            }
            md.append(values).append('\n');
        });

        md.append("\n## Error rates by threshold\n\n| Threshold | FRR e2e | Skilled FAR e2e | Random FAR e2e | FAR upper bound |\n| --- | --- | --- | --- | --- |\n");
        for (CalibrationEvaluator.Rates rates : r.curve()) {
            if (Math.round(rates.threshold() * 100) % 10 == 0) {
                md.append(String.format(Locale.ROOT, "| %.1f | %s | %s | %s | %s |%n", rates.threshold(),
                        pct(rates.frrEndToEnd()), pct(rates.skilledFarEndToEnd()), pct(rates.randomFarEndToEnd()),
                        pct(rates.farUpperBound())));
            }
        }
        return md.toString();
    }

    private static void row(StringBuilder md, String label, CalibrationEvaluator.Rates r) {
        md.append(String.format(Locale.ROOT, "| %s | %.2f | %s | %s | %s | %s |%n", label, r.threshold(), pct(r.frr()),
                pct(r.frrEndToEnd()), pct(r.skilledFarEndToEnd()), pct(r.randomFarEndToEnd())));
    }

    private static String pct(double v) {
        return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%.1f%%", v * 100);
    }
}
