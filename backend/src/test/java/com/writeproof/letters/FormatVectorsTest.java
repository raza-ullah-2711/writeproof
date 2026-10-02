package com.writeproof.letters;

import static org.assertj.core.api.Assertions.assertThat;

import com.writeproof.common.Base64Url;
import com.writeproof.identity.EncryptionKeyBinding;
import com.writeproof.ledger.LedgerHashing;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Byte formats shared with the frontend. The same vectors are asserted in
 * frontend/src/app/letters/letter-format.spec.ts and ledger-verify.spec.ts; change both or neither.
 */
class FormatVectorsTest {

    static byte[] bytes(int from, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (from + i);
        }
        return b;
    }

    static final LetterEnvelope ENVELOPE = new LetterEnvelope(1,
            Base64Url.encode(bytes(1, 12)),
            Base64Url.encode(bytes(50, 40)),
            new LetterEnvelope.WrappedKey(Base64Url.encode(bytes(100, 32)), Base64Url.encode(bytes(140, 12)),
                    Base64Url.encode(bytes(160, 48))),
            new LetterEnvelope.WrappedKey(Base64Url.encode(bytes(200, 32)), Base64Url.encode(bytes(240, 12)),
                    Base64Url.encode(bytes(10, 48))));

    @Test
    void ledgerEntryHash() {
        byte[] hash = LedgerHashing.entryHash(3, bytes(0, 32), bytes(32, 32), Instant.ofEpochMilli(1_790_000_000_123L));

        assertThat(Base64Url.encode(hash)).isEqualTo("YkX8efNwvw09CuvGvt9Cs6Lllmff-CZT-urM_XkFWlY");
    }

    @Test
    void letterHeaderHashAndSignedMessage() {
        String header = LetterHashing.header(bytes(1, 32), bytes(101, 32), "2026-10-02T12:00:00.000Z");
        byte[] hash = LetterHashing.letterHash(header, ENVELOPE);

        assertThat(header).isEqualTo("writeproof/letter/v1\n"
                + "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA\n"
                + "ZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5_gIGCg4Q\n"
                + "2026-10-02T12:00:00.000Z");
        assertThat(Base64Url.encode(hash)).isEqualTo("9EcA6cSC4xiiDwmYh6svDBId42vzq_j1zMPjTcZZmTs");
        assertThat(new String(LetterHashing.signedMessage(hash), StandardCharsets.UTF_8))
                .isEqualTo("writeproof/letter-signature/v1\n9EcA6cSC4xiiDwmYh6svDBId42vzq_j1zMPjTcZZmTs");
    }

    @Test
    void handSignedLetterHeaderAndHash() {
        byte[] handwritingHash = LetterHashing.handwritingHash("{\"format\":\"writeproof.handwriting\"}");
        String header = LetterHashing.headerV2(bytes(1, 32), bytes(101, 32), "2026-10-02T12:00:00.000Z", handwritingHash);

        assertThat(Base64Url.encode(handwritingHash)).isEqualTo("ENPXVYNKkitwzrhusxhzz4zgCM6PlNr31tF_pUxw9QQ");
        assertThat(header).isEqualTo("writeproof/letter/v2\n"
                + "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA\n"
                + "ZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5_gIGCg4Q\n"
                + "2026-10-02T12:00:00.000Z\n"
                + "ENPXVYNKkitwzrhusxhzz4zgCM6PlNr31tF_pUxw9QQ");
        assertThat(Base64Url.encode(LetterHashing.letterHash(header, ENVELOPE)))
                .isEqualTo("0POqYGKL8NfQmv7K-jvj8VrFCxClE7yi8Tu3WRxPMKE");
    }

    @Test
    void replyHeaderAndHash() {
        byte[] handwritingHash = LetterHashing.handwritingHash("{\"format\":\"writeproof.handwriting\"}");
        String header = LetterHashing.headerV3(bytes(101, 32), bytes(1, 32), "2026-10-02T12:05:00.000Z",
                handwritingHash, Base64Url.decode("0POqYGKL8NfQmv7K-jvj8VrFCxClE7yi8Tu3WRxPMKE"));

        assertThat(header).isEqualTo("writeproof/letter/v3\n"
                + "ZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5_gIGCg4Q\n"
                + "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA\n"
                + "2026-10-02T12:05:00.000Z\n"
                + "ENPXVYNKkitwzrhusxhzz4zgCM6PlNr31tF_pUxw9QQ\n"
                + "0POqYGKL8NfQmv7K-jvj8VrFCxClE7yi8Tu3WRxPMKE");
        assertThat(Base64Url.encode(LetterHashing.letterHash(header, ENVELOPE)))
                .isEqualTo("o9Kh3vyRofTJuiyDzC6mG2PPLAiIGq5wHo0iv9KT4fQ");
    }

    @Test
    void encryptionKeyBinding() {
        assertThat(new String(EncryptionKeyBinding.of(bytes(1, 32), bytes(101, 32)), StandardCharsets.UTF_8))
                .isEqualTo("writeproof/encryption-key/v1\n"
                        + "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA\n"
                        + "ZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5_gIGCg4Q");
    }
}
