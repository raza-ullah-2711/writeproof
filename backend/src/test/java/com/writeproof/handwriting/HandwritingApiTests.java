package com.writeproof.handwriting;

import static org.assertj.core.api.Assertions.assertThat;

import com.writeproof.TestWallet;
import com.writeproof.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HandwritingApiTests {

    @Autowired
    private TestRestTemplate rest;

    private String token;
    private final SyntheticSignatures.Writer writer = new SyntheticSignatures.Writer(2024);

    @BeforeEach
    void logIn() throws Exception {
        token = TestWallet.registerAndLogIn(rest);
    }

    private List<HandwritingSample> enrolmentSamples() {
        return List.of(writer.genuine(1, "pen"), writer.genuine(2, "pen"), writer.genuine(3, "pen"));
    }

    @Test
    void enrolThenVerifyTheGenuineWriter() {
        assertThat(getEnrolment().getBody()).containsEntry("enrolled", false);

        ResponseEntity<Map> enrolled = post("/api/handwriting/enrolment", Map.of("samples", enrolmentSamples()));
        assertThat(enrolled.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(enrolled.getBody()).containsEntry("sampleCount", 3);
        assertThat(getEnrolment().getBody()).containsEntry("enrolled", true);

        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) verify(writer.genuine(99, "pen")).getBody();
        assertThat(result).containsEntry("verified", true).containsEntry("match", true).containsEntry("live", true);
        assertThat((Double) result.get("score")).isGreaterThanOrEqualTo((Double) result.get("threshold"));
        assertThat((List<Object>) result.get("livenessFlags")).isEmpty();
    }

    @Test
    void aForgeryIsNotVerified() {
        post("/api/handwriting/enrolment", Map.of("samples", enrolmentSamples()));

        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) verify(SyntheticSignatures.skilledForgery(writer, 7, "pen")).getBody();

        assertThat(result).containsEntry("verified", false).containsEntry("match", false);
    }

    @Test
    void replayingAnEnrolledSampleIsNotVerified() {
        List<HandwritingSample> samples = enrolmentSamples();
        post("/api/handwriting/enrolment", Map.of("samples", samples));

        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) verify(samples.get(1)).getBody();

        assertThat(result).containsEntry("verified", false).containsEntry("live", false);
        assertThat((List<Object>) result.get("livenessFlags")).contains("REPLAY");
    }

    @Test
    void scriptedEnrolmentSamplesAreRejectedWithReasons() {
        HandwritingSample bot = SyntheticSignatures.bot(writer, "pen");
        ResponseEntity<Map> response = post("/api/handwriting/enrolment",
                Map.of("samples", List.of(writer.genuine(1, "pen"), bot, writer.genuine(3, "pen"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("sampleIndex", 1);
        assertThat((List<Object>) response.getBody().get("livenessFlags")).contains("CONSTANT_VELOCITY");
        assertThat(getEnrolment().getBody()).containsEntry("enrolled", false);
    }

    @Test
    void enrolmentIsOneTimeAndNeedsThreeToFiveSamples() {
        HandwritingSample one = writer.genuine(1, "pen");
        assertThat(post("/api/handwriting/enrolment", Map.of("samples", List.of(one, writer.genuine(2, "pen"))))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/handwriting/enrolment", Map.of("samples", List.of(one, one, writer.genuine(2, "pen"))))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(post("/api/handwriting/enrolment", Map.of("samples", enrolmentSamples())).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(post("/api/handwriting/enrolment", Map.of("samples", enrolmentSamples())).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void verifyingBeforeEnrolmentIsNotFound() {
        assertThat(verify(writer.genuine(1, "pen")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void malformedSamplesAreRejected() {
        HandwritingSample good = writer.genuine(1, "pen");
        HandwritingSample wrongFormat = new HandwritingSample("png", 1, good.capturedAt(), good.device(),
                good.width(), good.height(), good.strokes());

        assertThat(verify(wrongFormat).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void requiresAuthentication() {
        token = null;
        assertThat(getEnrolment().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(verify(writer.genuine(1, "pen")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Map> verify(HandwritingSample sample) {
        return post("/api/handwriting/verify", Map.of("sample", sample));
    }

    private ResponseEntity<Map> post(String path, Object body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers()), Map.class);
    }

    private ResponseEntity<Map> getEnrolment() {
        return rest.exchange("/api/handwriting/enrolment", HttpMethod.GET, new HttpEntity<>(headers()), Map.class);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }
}
