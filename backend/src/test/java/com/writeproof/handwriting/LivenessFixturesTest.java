package com.writeproof.handwriting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.openletters.HandwrittenPage;
import java.io.InputStream;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the liveness checks to two fixture pages, shared with the browser's port
 * (frontend liveness.spec.ts, same files and numbers): the recipient of a sealed letter runs the
 * checks in their browser, so both must judge a page the same way.
 */
class LivenessFixturesTest {

    private static HandwritingSample load(String name) throws Exception {
        try (InputStream in = LivenessFixturesTest.class.getResourceAsStream("/liveness/" + name)) {
            return new ObjectMapper().readValue(in, HandwrittenPage.class).validate(true).toSample();
        }
    }

    @Test
    void aLivePageHasNoFlags() throws Exception {
        HandwritingSample s = load("live-page.json");
        Features f = FeatureExtractor.extract(s);

        assertThat(Stats.withinStrokeSpeedCv(f)).isCloseTo(0.5684741784282148, within(1e-9));
        assertThat(f.durationMs()).isEqualTo(8585.0);
        assertThat(f.pointCount()).isEqualTo(1627);
        assertThat(LivenessChecker.check(s, f)).isEmpty();
    }

    @Test
    void aScriptedPageIsFlagged() throws Exception {
        HandwritingSample s = load("scripted-page.json");
        Features f = FeatureExtractor.extract(s);

        assertThat(Stats.withinStrokeSpeedCv(f)).isCloseTo(0.10822351085246053, within(1e-9));
        assertThat(LivenessChecker.check(s, f))
                .isEqualTo(Set.of(LivenessFlag.CONSTANT_VELOCITY, LivenessFlag.CONSTANT_PRESSURE));
    }
}
