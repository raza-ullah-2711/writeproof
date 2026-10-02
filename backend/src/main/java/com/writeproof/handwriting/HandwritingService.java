package com.writeproof.handwriting;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HandwritingService {

    public record Enrolled(int sampleCount, Instant enrolledAt) {}

    public record Verification(
            double score,
            double threshold,
            boolean match,
            boolean live,
            Set<LivenessFlag> livenessFlags,
            boolean verified,
            SignatureMatcher.Match details) {}

    /** Thrown when enrolment samples don't look hand-written live; carries the reasons. */
    public static class NotLiveException extends ResponseStatusException {
        public final int sampleIndex;
        public final Set<LivenessFlag> flags;

        NotLiveException(int sampleIndex, Set<LivenessFlag> flags) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, "Sample " + (sampleIndex + 1) + " failed liveness checks: " + flags);
            this.sampleIndex = sampleIndex;
            this.flags = flags;
        }
    }

    static final Duration MAX_SAMPLE_AGE = Duration.ofMinutes(10);
    static final int HISTORY_SIZE = 20;

    private final EnrolmentRepository enrolments;
    private final SignatureHistoryRepository history;
    private final HandwritingProperties properties;
    private final Clock clock;

    HandwritingService(EnrolmentRepository enrolments, SignatureHistoryRepository history,
                       HandwritingProperties properties, Clock clock) {
        this.enrolments = enrolments;
        this.history = history;
        this.properties = properties;
        this.clock = clock;
    }

    public Enrolled enrol(UUID accountId, List<HandwritingSample> samples) {
        if (enrolments.find(accountId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Handwriting is already enrolled");
        }
        List<Features> features = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            HandwritingSample sample = samples.get(i).validate();
            Features f = FeatureExtractor.extract(sample);
            Set<LivenessFlag> flags = LivenessChecker.check(sample, f);
            if (!flags.isEmpty()) {
                throw new NotLiveException(i, flags);
            }
            features.add(f);
        }
        SignatureMatcher.enrol(features); // size and duplicate checks
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!enrolments.insertIfAbsent(new EnrolmentRepository.Enrolment(accountId, List.copyOf(samples), now))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Handwriting is already enrolled");
        }
        return new Enrolled(samples.size(), now);
    }

    public Optional<Enrolled> enrolment(UUID accountId) {
        return enrolments.find(accountId).map(e -> new Enrolled(e.samples().size(), e.createdAt()));
    }

    public Verification verify(UUID accountId, HandwritingSample sample) {
        Evaluation e = evaluate(accountId, sample);
        return e.verification(properties.threshold());
    }

    /**
     * Verifies a signature written to seal a letter. On top of {@link #verify}, the sample must
     * have been captured within {@link #MAX_SAMPLE_AGE} and must not be a copy of one of the
     * account's recent letter signatures.
     */
    public Verification verifyForLetter(UUID accountId, HandwritingSample sample) {
        Evaluation e = evaluate(accountId, sample);
        Instant capturedAt;
        try {
            capturedAt = Instant.parse(sample.capturedAt());
        } catch (java.time.format.DateTimeParseException ex) {
            throw new IllegalArgumentException("capturedAt must be an ISO-8601 instant");
        }
        if (Duration.between(capturedAt, clock.instant()).abs().compareTo(MAX_SAMPLE_AGE) > 0) {
            e.flags().add(LivenessFlag.STALE);
        }
        for (HandwritingSample previous : history.recent(accountId, HISTORY_SIZE)) {
            double distance = Dtw.distance(FeatureExtractor.extract(previous).frames(), e.query().frames());
            if (distance / e.template().spread() < SignatureMatcher.REPLAY_RATIO) {
                e.flags().add(LivenessFlag.REPLAY);
                break;
            }
        }
        return e.verification(properties.threshold());
    }

    /** Remembers a signature that sealed a letter, keeping only the newest {@link #HISTORY_SIZE}. */
    public void recordLetterSignature(UUID accountId, HandwritingSample sample) {
        history.add(accountId, sample, clock.instant(), HISTORY_SIZE);
    }

    private record Evaluation(
            SignatureMatcher.Template template, Features query, SignatureMatcher.Match match, Set<LivenessFlag> flags) {

        Verification verification(double threshold) {
            boolean matches = match.score() >= threshold;
            boolean live = flags.isEmpty();
            return new Verification(match.score(), threshold, matches, live, Set.copyOf(flags), matches && live, match);
        }
    }

    private Evaluation evaluate(UUID accountId, HandwritingSample sample) {
        Features query = FeatureExtractor.extract(sample.validate());
        EnrolmentRepository.Enrolment enrolment = enrolments.find(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Handwriting is not enrolled"));
        SignatureMatcher.Template template = SignatureMatcher.enrol(
                enrolment.samples().stream().map(FeatureExtractor::extract).toList());
        SignatureMatcher.Match match = SignatureMatcher.match(template, query);

        Set<LivenessFlag> flags = EnumSet.noneOf(LivenessFlag.class);
        flags.addAll(LivenessChecker.check(sample, query));
        if (SignatureMatcher.isReplay(match)) {
            flags.add(LivenessFlag.REPLAY);
        }
        return new Evaluation(template, query, match, flags);
    }
}
