package com.writeproof.openletters;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Read access to an open letter's handwritten pages for moderation and preservation: unlike the
 * public view, they are returned during a takedown hold too (until they're blanked).
 */
@Component
public class OpenLetterPages {

    private final OpenLetterRepository letters;

    OpenLetterPages(OpenLetterRepository letters) {
        this.letters = letters;
    }

    /** The pages still stored, each with its timing hash and, if the author allowed replay, its timing. */
    public List<HandwrittenPage> of(byte[] letterHash) {
        return letters.pages(letterHash);
    }
}
