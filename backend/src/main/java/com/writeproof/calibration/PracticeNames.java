package com.writeproof.calibration;

import java.security.SecureRandom;
import java.util.List;

/** Made-up names contributors write instead of their real signature. */
final class PracticeNames {

    private static final List<String> FIRST = List.of("Ada", "Bram", "Cleo", "Dov", "Edda", "Fitz", "Gwen",
            "Hal", "Ines", "Joss", "Kit", "Lark", "Milo", "Nell", "Otis", "Pia", "Quin", "Rhea", "Saul", "Tove");
    private static final List<String> LAST = List.of("Quill", "Marsh", "Vale", "Rook", "Penner", "Lowe", "Ashby",
            "Crane", "Dunmore", "Fenwick", "Galloway", "Hollis", "Ingram", "Juniper", "Kestrel", "Lindqvist");

    private static final SecureRandom RANDOM = new SecureRandom();

    private PracticeNames() {}

    static String next() {
        return FIRST.get(RANDOM.nextInt(FIRST.size())) + " " + LAST.get(RANDOM.nextInt(LAST.size()));
    }
}
