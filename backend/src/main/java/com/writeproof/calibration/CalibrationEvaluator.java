package com.writeproof.calibration;

import com.writeproof.handwriting.FeatureExtractor;
import com.writeproof.handwriting.Features;
import com.writeproof.handwriting.LivenessChecker;
import com.writeproof.handwriting.LivenessFlag;
import com.writeproof.handwriting.SignatureMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Measures the real matcher and liveness checks on a labelled dataset.
 *
 * <p>Protocol, per writer: enrol from the first {@code enrolSize} genuine samples of their
 * earliest session; probe with every remaining genuine sample (same-session and later
 * sessions), every skilled forgery that targeted them, and one genuine sample from each other
 * writer (random forgeries). A probe is <em>accepted</em> when its score reaches the threshold
 * and, end to end, also passes liveness.
 */
public final class CalibrationEvaluator {

    public enum ProbeKind { GENUINE, SKILLED, RANDOM }

    /** One probe's outcome. {@code crossSession}/{@code crossDevice}: relative to enrolment. */
    public record Probe(String writer, ProbeKind kind, double score, Set<LivenessFlag> flags, String device,
                        boolean crossSession, boolean crossDevice) {

        boolean live() {
            return flags.isEmpty();
        }
    }

    /**
     * Error rates at one threshold. FAR = forgeries accepted, FRR = genuine rejected.
     * {@code farUpperBound}: 95% Wilson upper bound on the end-to-end FAR (skilled and random
     * pooled), so a handful of forgeries can't make a threshold look safer than it is.
     */
    public record Rates(double threshold, double frr, double frrEndToEnd, double skilledFar,
                        double skilledFarEndToEnd, double randomFar, double randomFarEndToEnd, double farUpperBound) {}

    public record Report(
            String source,
            int writers,
            int writersSkipped,
            Map<ProbeKind, Integer> probes,
            Rates atConfigured,
            double eer,
            double eerThreshold,
            Double recommendedThreshold,
            Rates atRecommended,
            double targetFar,
            int forgeriesNeededForTarget,
            Map<String, Double> livenessFalseRejects,
            Map<String, Double> frrByProbeDevice,
            double frrCrossSession,
            double frrCrossDevice,
            Map<ProbeKind, double[]> scoreDeciles,
            List<Rates> curve) {}

    private final int enrolSize;
    private final double configuredThreshold;
    private final double targetFar;

    public CalibrationEvaluator(int enrolSize, double configuredThreshold, double targetFar) {
        if (enrolSize < SignatureMatcher.MIN_REFERENCES || enrolSize > SignatureMatcher.MAX_REFERENCES) {
            throw new IllegalArgumentException("enrolSize must be 3..5");
        }
        this.enrolSize = enrolSize;
        this.configuredThreshold = configuredThreshold;
        this.targetFar = targetFar;
    }

    public Report evaluate(CalibrationDataset dataset) {
        dataset.validate();
        Map<String, List<CalibrationDataset.Entry>> genuineByWriter = new LinkedHashMap<>();
        Map<String, List<CalibrationDataset.Entry>> forgeriesOfWriter = new LinkedHashMap<>();
        for (CalibrationDataset.Entry e : dataset.samples()) {
            if (e.genuine()) {
                genuineByWriter.computeIfAbsent(e.writer(), w -> new ArrayList<>()).add(e);
            } else {
                forgeriesOfWriter.computeIfAbsent(e.target(), w -> new ArrayList<>()).add(e);
            }
        }

        List<Probe> probes = new ArrayList<>();
        int writers = 0;
        int skipped = 0;
        for (Map.Entry<String, List<CalibrationDataset.Entry>> w : genuineByWriter.entrySet()) {
            List<CalibrationDataset.Entry> genuine = new ArrayList<>(w.getValue());
            // Stable sort: dataset order within a session is the order the samples were written.
            genuine.sort(Comparator.comparing(CalibrationDataset.Entry::session));
            if (genuine.size() <= enrolSize) {
                skipped++;
                continue;
            }
            List<CalibrationDataset.Entry> enrolment = genuine.subList(0, enrolSize);
            String enrolSession = enrolment.getFirst().session();
            String enrolDevice = enrolment.getFirst().sample().device();
            SignatureMatcher.Template template;
            try {
                template = SignatureMatcher.enrol(enrolment.stream().map(e -> FeatureExtractor.extract(e.sample())).toList());
            } catch (IllegalArgumentException duplicateReferences) {
                skipped++;
                continue;
            }
            writers++;
            for (CalibrationDataset.Entry g : genuine.subList(enrolSize, genuine.size())) {
                probes.add(probe(w.getKey(), ProbeKind.GENUINE, template, g, enrolSession, enrolDevice));
            }
            for (CalibrationDataset.Entry f : forgeriesOfWriter.getOrDefault(w.getKey(), List.of())) {
                probes.add(probe(w.getKey(), ProbeKind.SKILLED, template, f, enrolSession, enrolDevice));
            }
            for (Map.Entry<String, List<CalibrationDataset.Entry>> other : genuineByWriter.entrySet()) {
                if (!other.getKey().equals(w.getKey())) {
                    probes.add(probe(w.getKey(), ProbeKind.RANDOM, template, other.getValue().getFirst(),
                            enrolSession, enrolDevice));
                }
            }
        }
        return report(dataset.source(), writers, skipped, probes);
    }

    private static Probe probe(String writer, ProbeKind kind, SignatureMatcher.Template template,
                               CalibrationDataset.Entry entry, String enrolSession, String enrolDevice) {
        Features features = FeatureExtractor.extract(entry.sample());
        SignatureMatcher.Match match = SignatureMatcher.match(template, features);
        Set<LivenessFlag> flags = java.util.EnumSet.noneOf(LivenessFlag.class);
        flags.addAll(LivenessChecker.check(entry.sample(), features));
        if (SignatureMatcher.isReplay(match)) {
            flags.add(LivenessFlag.REPLAY);
        }
        return new Probe(writer, kind, match.score(), flags, entry.sample().device(),
                !entry.session().equals(enrolSession), !entry.sample().device().equals(enrolDevice));
    }

    private Report report(String source, int writers, int skipped, List<Probe> probes) {
        Map<ProbeKind, List<Probe>> byKind = new EnumMap<>(ProbeKind.class);
        for (ProbeKind k : ProbeKind.values()) {
            byKind.put(k, probes.stream().filter(p -> p.kind() == k).toList());
        }
        List<Rates> curve = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            curve.add(rates(i / 100.0, byKind));
        }

        // Equal error rate on skilled forgeries if there are any, else random ones (match only).
        // Where several thresholds tie (e.g. perfectly separated data), take the middle one.
        boolean haveSkilled = !byKind.get(ProbeKind.SKILLED).isEmpty();
        java.util.function.ToDoubleFunction<Rates> gap =
                r -> Math.abs(r.frr() - (haveSkilled ? r.skilledFar() : r.randomFar()));
        // NaN gaps mean there are no probes of that kind: nothing to measure.
        double bestGap = curve.stream().mapToDouble(gap).filter(d -> !Double.isNaN(d)).min().orElse(Double.NaN);
        List<Rates> ties = curve.stream().filter(r -> gap.applyAsDouble(r) <= bestGap + 1e-12).toList();
        Rates eerPoint = ties.isEmpty() ? null : ties.get(ties.size() / 2);
        double eer = eerPoint == null ? Double.NaN
                : (eerPoint.frr() + (haveSkilled ? eerPoint.skilledFar() : eerPoint.randomFar())) / 2;

        // Lowest threshold whose false-accept upper bound meets the target: the most genuine
        // writers accepted, among thresholds the data actually supports.
        Rates recommended = curve.stream().filter(r -> r.farUpperBound() <= targetFar).findFirst().orElse(null);
        int forgeries = byKind.get(ProbeKind.SKILLED).size() + byKind.get(ProbeKind.RANDOM).size();

        List<Probe> genuine = byKind.get(ProbeKind.GENUINE);
        Map<String, Double> livenessFalseRejects = new TreeMap<>();
        for (LivenessFlag flag : LivenessFlag.values()) {
            long n = genuine.stream().filter(p -> p.flags().contains(flag)).count();
            if (n > 0) {
                livenessFalseRejects.put(flag.name(), (double) n / genuine.size());
            }
        }
        Map<String, Double> frrByDevice = genuine.stream().collect(Collectors.groupingBy(Probe::device,
                TreeMap::new, Collectors.collectingAndThen(Collectors.toList(), this::frr)));

        Map<ProbeKind, double[]> deciles = new EnumMap<>(ProbeKind.class);
        byKind.forEach((k, list) -> deciles.put(k, deciles(list)));
        Map<ProbeKind, Integer> counts = new EnumMap<>(ProbeKind.class);
        byKind.forEach((k, list) -> counts.put(k, list.size()));

        return new Report(source, writers, skipped, counts, rates(configuredThreshold, byKind), eer,
                eerPoint == null ? Double.NaN : eerPoint.threshold(),
                recommended == null ? null : recommended.threshold(), recommended, targetFar,
                Math.max(0, forgeriesNeeded(targetFar) - forgeries),
                livenessFalseRejects, frrByDevice,
                frr(genuine.stream().filter(Probe::crossSession).toList()),
                frr(genuine.stream().filter(Probe::crossDevice).toList()),
                deciles, curve);
    }

    private double frr(List<Probe> genuine) {
        return genuine.isEmpty() ? Double.NaN
                : genuine.stream().filter(p -> !(p.score() >= configuredThreshold && p.live())).count()
                        / (double) genuine.size();
    }

    static Rates rates(double t, Map<ProbeKind, List<Probe>> byKind) {
        List<Probe> g = byKind.get(ProbeKind.GENUINE);
        List<Probe> s = byKind.get(ProbeKind.SKILLED);
        List<Probe> r = byKind.get(ProbeKind.RANDOM);
        long accepted = s.stream().filter(p -> p.score() >= t && p.live()).count()
                + r.stream().filter(p -> p.score() >= t && p.live()).count();
        return new Rates(t,
                fraction(g, p -> p.score() < t),
                fraction(g, p -> p.score() < t || !p.live()),
                fraction(s, p -> p.score() >= t),
                fraction(s, p -> p.score() >= t && p.live()),
                fraction(r, p -> p.score() >= t),
                fraction(r, p -> p.score() >= t && p.live()),
                wilsonUpper(accepted, s.size() + r.size()));
    }

    private static final double Z = 1.96;

    /** 95% Wilson score upper bound for {@code k} successes in {@code n} trials (1 when n = 0). */
    static double wilsonUpper(long k, long n) {
        if (n == 0) {
            return 1;
        }
        double p = (double) k / n;
        double z2 = Z * Z;
        double centre = p + z2 / (2 * n);
        double margin = Z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n));
        return Math.min(1, (centre + margin) / (1 + z2 / n));
    }

    /** Forgery probes needed for a clean run (none accepted) to bound FAR at {@code target}. */
    static int forgeriesNeeded(double target) {
        int n = 1;
        while (wilsonUpper(0, n) > target) {
            n++;
        }
        return n;
    }

    private static double fraction(List<Probe> probes, java.util.function.Predicate<Probe> test) {
        return probes.isEmpty() ? Double.NaN : probes.stream().filter(test).count() / (double) probes.size();
    }

    private static double[] deciles(List<Probe> probes) {
        double[] sorted = probes.stream().mapToDouble(Probe::score).sorted().toArray();
        double[] out = new double[11];
        for (int i = 0; i <= 10; i++) {
            out[i] = sorted.length == 0 ? Double.NaN
                    : sorted[Math.min(sorted.length - 1, (int) Math.round(i / 10.0 * (sorted.length - 1)))];
        }
        return out;
    }
}
