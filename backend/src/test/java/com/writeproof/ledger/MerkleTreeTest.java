package com.writeproof.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class MerkleTreeTest {

    private static final int MAX = 33; // covers several power-of-two boundaries

    /** Leaf hashes for leaves whose data is the single byte i. */
    private static List<byte[]> leaves(int n) {
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(MerkleTree.leafHash(new byte[] {(byte) i}));
        }
        return out;
    }

    private static byte[] flip(byte[] b) {
        byte[] c = b.clone();
        c[0] ^= 1;
        return c;
    }

    @Test
    void matchesTheRfcStructureForSmallTrees() throws Exception {
        List<byte[]> l = leaves(3);
        byte[] expected = MerkleTree.nodeHash(MerkleTree.nodeHash(l.get(0), l.get(1)), l.get(2));
        assertThat(MerkleTree.root(l)).isEqualTo(expected);
        assertThat(MerkleTree.root(List.of())).isEqualTo(MessageDigest.getInstance("SHA-256").digest());
        assertThat(MerkleTree.root(leaves(1))).isEqualTo(leaves(1).getFirst());
    }

    @Test
    void everyInclusionProofVerifiesAndNoTamperedOneDoes() {
        List<byte[]> all = leaves(MAX);
        for (int size = 1; size <= MAX; size++) {
            byte[] root = MerkleTree.root(all.subList(0, size));
            for (int i = 0; i < size; i++) {
                List<byte[]> proof = MerkleTree.inclusionProof(all, i, size);
                assertThat(MerkleTree.verifyInclusion(i, size, all.get(i), proof, root))
                        .as("index %d size %d", i, size).isTrue();
                // Wrong leaf, wrong index, wrong root, tampered path: all rejected.
                assertThat(MerkleTree.verifyInclusion(i, size, flip(all.get(i)), proof, root)).isFalse();
                if (size > 1) {
                    assertThat(MerkleTree.verifyInclusion((i + 1) % size, size, all.get(i), proof, root)).isFalse();
                }
                assertThat(MerkleTree.verifyInclusion(i, size, all.get(i), proof, flip(root))).isFalse();
                if (!proof.isEmpty()) {
                    List<byte[]> bad = new ArrayList<>(proof);
                    bad.set(0, flip(bad.getFirst()));
                    assertThat(MerkleTree.verifyInclusion(i, size, all.get(i), bad, root)).isFalse();
                }
            }
        }
    }

    @Test
    void everyConsistencyProofVerifiesAndRewrittenHistoryDoesNot() {
        List<byte[]> all = leaves(MAX);
        for (int newSize = 1; newSize <= MAX; newSize++) {
            byte[] newRoot = MerkleTree.root(all.subList(0, newSize));
            for (int oldSize = 1; oldSize <= newSize; oldSize++) {
                byte[] oldRoot = MerkleTree.root(all.subList(0, oldSize));
                List<byte[]> proof = MerkleTree.consistencyProof(all, oldSize, newSize);
                assertThat(MerkleTree.verifyConsistency(oldSize, newSize, oldRoot, newRoot, proof))
                        .as("%d -> %d", oldSize, newSize).isTrue();
                assertThat(MerkleTree.verifyConsistency(oldSize, newSize, flip(oldRoot), newRoot, proof)).isFalse();
                assertThat(MerkleTree.verifyConsistency(oldSize, newSize, oldRoot, flip(newRoot), proof)).isFalse();

                // A rewritten history: one old leaf changed, everything recomputed consistently.
                if (oldSize < newSize) {
                    List<byte[]> rewritten = new ArrayList<>(all.subList(0, newSize));
                    rewritten.set(oldSize - 1, flip(rewritten.get(oldSize - 1)));
                    byte[] forgedRoot = MerkleTree.root(rewritten);
                    List<byte[]> forgedProof = MerkleTree.consistencyProof(rewritten, oldSize, newSize);
                    assertThat(MerkleTree.verifyConsistency(oldSize, newSize, oldRoot, forgedRoot, forgedProof))
                            .as("rewrite %d -> %d", oldSize, newSize).isFalse();
                }
            }
        }
    }

    /** The leaf data used by Certificate Transparency's reference test vectors. */
    private static final String[] CT_LEAVES = {"", "00", "10", "2021", "3031", "40414243",
            "5051525354555657", "606162636465666768696a6b6c6d6e6f"};

    @Test
    void matchesCertificateTransparencyReferenceRoots() {
        HexFormat hex = HexFormat.of();
        List<byte[]> l = new ArrayList<>();
        for (String data : CT_LEAVES) {
            l.add(MerkleTree.leafHash(hex.parseHex(data)));
        }
        assertThat(hex.formatHex(MerkleTree.root(l.subList(0, 1))))
                .isEqualTo("6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d");
        assertThat(hex.formatHex(MerkleTree.root(l)))
                .isEqualTo("5dc9da79a70659a9ad559cb701ded9a2ab9d823aad2f4960cfe370eff4604328");
    }

    @Test
    void sharedVectorsWithTheFrontend() {
        // Same vectors as frontend/src/app/letters/merkle.spec.ts: leaves are bytes 0..6.
        List<byte[]> l = leaves(7);
        HexFormat hex = HexFormat.of();
        assertThat(hex.formatHex(MerkleTree.root(l)))
                .isEqualTo("3560191803028444b232018ac047fdb561c09c23a7a6876c85e08b5e4d48e9f3");
        assertThat(MerkleTree.inclusionProof(l, 3, 7).stream().map(hex::formatHex)).containsExactly(
                "fcf0a6c700dd13e274b6fba8deea8dd9b26e4eedde3495717cac8408c9c5177f",
                "a20bf9a7cc2dc8a08f5f415a71b19f6ac427bab54d24eec868b5d3103449953a",
                "89c929834ed1459b07f65b5e1a2143a8cf5d8efdf30f49ffffa328bb1d9133bb");
        assertThat(MerkleTree.consistencyProof(l, 3, 7).stream().map(hex::formatHex)).containsExactly(
                "fcf0a6c700dd13e274b6fba8deea8dd9b26e4eedde3495717cac8408c9c5177f",
                "583c7dfb7b3055d99465544032a571e10a134b1b6f769422bbb71fd7fa167a5d",
                "a20bf9a7cc2dc8a08f5f415a71b19f6ac427bab54d24eec868b5d3103449953a",
                "89c929834ed1459b07f65b5e1a2143a8cf5d8efdf30f49ffffa328bb1d9133bb");
    }

}
