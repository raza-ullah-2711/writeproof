package com.writeproof.handwriting;

import java.util.List;

/**
 * Fuzzy matching against an enrolled template (architecture rule 4: a similarity score and a
 * threshold, never equality).
 *
 * <p>The DTW distance to the nearest reference is divided by the template's own spread (mean
 * pairwise distance between the references), so each person is judged against how much their
 * own handwriting varies. A careful forger who gets the shape right is usually far too slow,
 * so duration is scored too. The final score is the product of both, in [0, 1].
 */
public final class SignatureMatcher {

    public static final int MIN_REFERENCES = 3;
    public static final int MAX_REFERENCES = 5;

    /** Lower bound on a template's spread, so near-identical references don't make it brittle. */
    static final double MIN_SPREAD = 0.05;
    /** Distance ratio at which the shape score has fallen to 1/e. */
    static final double SHAPE_TOLERANCE = 0.35;
    /** Duration may differ from the enrolled mean by this factor before it costs anything. */
    static final double DURATION_FREE_RATIO = 1.35;
    static final double DURATION_TOLERANCE = 0.2;
    /** Nearest-reference ratio below this is a copy, not a new writing. Genuine samples sit >= 0.58. */
    static final double REPLAY_RATIO = 0.2;
    /** Two references closer than this (absolute) are the same sample submitted twice. */
    static final double DUPLICATE_DISTANCE = 0.01;

    public record Template(List<Features> references, double spread, double meanLogDuration, double logDurationStd) {}

    public record Match(double score, double shapeScore, double durationScore, double distanceRatio) {}

    private SignatureMatcher() {}

    /** @throws IllegalArgumentException for too few/many references or duplicated references */
    public static Template enrol(List<Features> references) {
        if (references.size() < MIN_REFERENCES || references.size() > MAX_REFERENCES) {
            throw new IllegalArgumentException(
                    "Enrolment needs " + MIN_REFERENCES + " to " + MAX_REFERENCES + " samples");
        }
        double total = 0;
        int pairs = 0;
        for (int i = 0; i < references.size(); i++) {
            for (int j = i + 1; j < references.size(); j++) {
                double d = Dtw.distance(references.get(i).frames(), references.get(j).frames());
                if (d < DUPLICATE_DISTANCE) {
                    throw new IllegalArgumentException("Enrolment samples must be written separately, not copied");
                }
                total += d;
                pairs++;
            }
        }
        double[] logDurations = references.stream().mapToDouble(f -> Math.log(f.durationMs())).toArray();
        return new Template(
                List.copyOf(references),
                Math.max(MIN_SPREAD, total / pairs),
                Stats.mean(logDurations),
                Stats.std(logDurations));
    }

    public static Match match(Template template, Features query) {
        double nearest = Double.POSITIVE_INFINITY;
        for (Features reference : template.references()) {
            nearest = Math.min(nearest, Dtw.distance(reference.frames(), query.frames()));
        }
        double ratio = nearest / template.spread();
        double shapeExcess = Math.max(0, ratio - 1) / SHAPE_TOLERANCE;
        double shapeScore = Math.exp(-shapeExcess * shapeExcess);

        double logRatio = Math.abs(Math.log(query.durationMs()) - template.meanLogDuration());
        double free = Math.max(Math.log(DURATION_FREE_RATIO), 3 * template.logDurationStd());
        double durationExcess = Math.max(0, logRatio - free) / DURATION_TOLERANCE;
        double durationScore = Math.exp(-durationExcess * durationExcess);

        return new Match(shapeScore * durationScore, shapeScore, durationScore, ratio);
    }

    /** Whether the closest reference is so close that the query must be a copy of it. */
    public static boolean isReplay(Match match) {
        return match.distanceRatio() < REPLAY_RATIO;
    }
}
