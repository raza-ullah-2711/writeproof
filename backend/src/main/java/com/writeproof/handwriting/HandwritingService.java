package com.writeproof.handwriting;

import java.time.Clock;
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

    private final EnrolmentRepository enrolments;
    private final HandwritingProperties properties;
    private final Clock clock;

    HandwritingService(EnrolmentRepository enrolments, HandwritingProperties properties, Clock clock) {
        this.enrolments = enrolments;
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
        boolean matches = match.score() >= properties.threshold();
        boolean live = flags.isEmpty();
        return new Verification(match.score(), properties.threshold(), matches, live, flags, matches && live, match);
    }
}
