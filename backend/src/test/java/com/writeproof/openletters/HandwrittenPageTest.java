package com.writeproof.openletters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.writeproof.common.Base64Url;
import com.writeproof.handwriting.FeatureExtractor;
import com.writeproof.handwriting.HandwritingSample;
import com.writeproof.handwriting.LivenessChecker;
import com.writeproof.handwriting.SyntheticSignatures;
import com.writeproof.letters.LetterHashing;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HandwrittenPageTest {

    private static HandwrittenPage vectorPage() {
        return new HandwrittenPage("writeproof.page", 1, "2026-10-13T09:12:44.120Z", "pen",
                new HandwrittenPage.Pen("fountain", "ink"),
                new int[][] {{100, 200, 128, 5, -3, 2, 4, 1, -1}, {300, 400, 90}},
                new long[][] {{0, 8, 9}, {120}}, null);
    }

    /** Shared with frontend page-format.spec.ts. */
    @Test
    void formatVector() {
        HandwrittenPage page = vectorPage().validate(true);
        byte[] inkHash = page.inkHash();
        byte[] timingHash = page.computeTimingHash();
        byte[] pageHash = HandwrittenPage.pageHash(inkHash, timingHash);
        byte[] pagesHash = OpenLetterHashing.pagesHash(true, List.of(pageHash));
        byte[] author = new byte[32];
        for (int i = 0; i < 32; i++) {
            author[i] = (byte) (i + 1);
        }
        byte[] handwritingHash = LetterHashing.handwritingHash("{\"format\":\"writeproof.handwriting\"}");

        assertThat(Base64Url.encode(inkHash)).isEqualTo("ycKsmUVD1_5sd6QCktQe8dELzGq9iLd5_cwRrjLyCfI");
        assertThat(Base64Url.encode(timingHash)).isEqualTo("c0buoYxwrOrV1dvcEWLK4WGKg0wqL4JmWkn8CJ5W0Fw");
        assertThat(Base64Url.encode(pageHash)).isEqualTo("hPrNA0307ki92W8crTC5kUAxoRslspn54WAb5vrfNt8");
        assertThat(Base64Url.encode(pagesHash)).isEqualTo("74nMQGqiddyXs7Vun80MoozcrbH2xJRkttZ-oa49eko");
        assertThat(Base64Url.encode(OpenLetterHashing.letterHashV2(author, "2026-10-13T09:20:00.000Z",
                handwritingHash, "Transcript ✍", pagesHash))).isEqualTo("jEFmyDrqqVWkPoh61ma9QPlME2oiBksqQ0XrzPivILk");
    }

    @Test
    void theReplayChoiceAndPageOrderAreBothCommitted() {
        byte[] a = HandwrittenPage.pageHash(new byte[32], new byte[32]);
        byte[] b = HandwrittenPage.pageHash(new byte[32], vectorPage().computeTimingHash());
        assertThat(OpenLetterHashing.pagesHash(true, List.of(a, b)))
                .isNotEqualTo(OpenLetterHashing.pagesHash(false, List.of(a, b)))
                .isNotEqualTo(OpenLetterHashing.pagesHash(true, List.of(b, a)));
    }

    @Test
    void decodesDeltasIntoPadUnitsForTheLivenessChecks() {
        HandwritingSample sample = vectorPage().toSample();

        assertThat(sample.width()).isEqualTo(1000);
        assertThat(sample.height()).isEqualTo(1414);
        var first = sample.strokes().getFirst();
        assertThat(first).hasSize(3);
        assertThat(first.get(2).x()).isEqualTo(10.9); // (100 + 5 + 4) / 10
        assertThat(first.get(2).y()).isEqualTo(19.8); // (200 - 3 + 1) / 10
        assertThat(first.get(2).t()).isEqualTo(17);
        assertThat(first.get(2).penDown()).isFalse();
        assertThat(first.get(1).penDown()).isTrue();
        assertThat(sample.strokes().get(1).getFirst().t()).isEqualTo(120);
    }

    @Test
    void rejectsPagesThatBreakTheFormat() {
        HandwrittenPage ok = vectorPage();
        HandwrittenPage.Pen pen = ok.pen();
        assertThatThrownBy(() -> new HandwrittenPage("writeproof.page", 2, ok.capturedAt(), "pen", pen, ok.ink(),
                ok.timing(), null).validate(true)).hasMessageContaining("v1");
        assertThatThrownBy(() -> new HandwrittenPage("writeproof.page", 1, ok.capturedAt(), "pen",
                new HandwrittenPage.Pen("quill", "ink"), ok.ink(), ok.timing(), null).validate(true))
                .hasMessageContaining("Unknown pen");
        assertThatThrownBy(() -> new HandwrittenPage("writeproof.page", 1, ok.capturedAt(), "pen", pen,
                new int[][] {{100, 200}}, new long[][] {{0}}, null).validate(true))
                .hasMessageContaining("x, y and pressure");
        assertThatThrownBy(() -> new HandwrittenPage("writeproof.page", 1, ok.capturedAt(), "pen", pen,
                new int[][] {{100, 200, 128, 0, 99_999, 0}}, new long[][] {{0, 5}}, null).validate(true))
                .hasMessageContaining("outside the page");
        assertThatThrownBy(() -> new HandwrittenPage("writeproof.page", 1, ok.capturedAt(), "pen", pen, ok.ink(),
                new long[][] {{0, 8, -9}, {120}}, null).validate(true)).hasMessageContaining("never decrease");
        assertThatThrownBy(() -> new HandwrittenPage("writeproof.page", 1, null, "pen", pen, ok.ink(), null, null)
                .validate(true)).hasMessageContaining("timing");
        // Stored pages may lack their timing (replay off).
        new HandwrittenPage("writeproof.page", 1, null, "pen", pen, ok.ink(), null, null).validate(false);
    }

    @Test
    void aPageOfRealLookingHandwritingPassesTheLivenessChecksAndAScriptedOneDoesNot() {
        SyntheticSignatures.Writer hand = new SyntheticSignatures.Writer(77);
        HandwrittenPage live = TestPages.page(Instant.now(), "fountain", "ink",
                List.of(hand.genuine(1, "pen"), hand.genuine(2, "pen"), hand.genuine(3, "pen")));
        HandwritingSample sample = live.validate(true).toSample();
        assertThat(LivenessChecker.check(sample, FeatureExtractor.extract(sample))).isEmpty();

        HandwrittenPage scripted = TestPages.page(Instant.now(), "fountain", "ink",
                List.of(SyntheticSignatures.bot(hand, "pen"), SyntheticSignatures.bot(hand, "pen")));
        HandwritingSample botSample = scripted.validate(true).toSample();
        assertThat(LivenessChecker.check(botSample, FeatureExtractor.extract(botSample))).isNotEmpty();
    }
}
