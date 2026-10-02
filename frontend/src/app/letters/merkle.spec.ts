import { TestMerkleTree } from '../../testing/merkle-tree';
import { leafHash, verifyConsistency, verifyInclusion } from './merkle';

const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
const unhex = (s: string) => Uint8Array.from(s.match(/../g) ?? [], (h) => parseInt(h, 16));

/** Leaves are the single bytes 0..n-1 (shared with backend MerkleTreeTest). */
const tree = (n: number) =>
  TestMerkleTree.of(Array.from({ length: n }, (_, i) => Uint8Array.of(i)));

describe('Merkle proofs', () => {
  it('matches the Certificate Transparency reference roots', async () => {
    const ct = [
      '',
      '00',
      '10',
      '2021',
      '3031',
      '40414243',
      '5051525354555657',
      '606162636465666768696a6b6c6d6e6f',
    ].map(unhex);
    expect(hex(await (await TestMerkleTree.of(ct)).root(1))).toBe(
      '6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d',
    );
    expect(hex(await (await TestMerkleTree.of(ct)).root())).toBe(
      '5dc9da79a70659a9ad559cb701ded9a2ab9d823aad2f4960cfe370eff4604328',
    );
  });

  it('verifies the proofs shared with the backend', async () => {
    const root7 = unhex('3560191803028444b232018ac047fdb561c09c23a7a6876c85e08b5e4d48e9f3');
    const a = unhex('fcf0a6c700dd13e274b6fba8deea8dd9b26e4eedde3495717cac8408c9c5177f');
    const b = unhex('a20bf9a7cc2dc8a08f5f415a71b19f6ac427bab54d24eec868b5d3103449953a');
    const c = unhex('89c929834ed1459b07f65b5e1a2143a8cf5d8efdf30f49ffffa328bb1d9133bb');
    const d = unhex('583c7dfb7b3055d99465544032a571e10a134b1b6f769422bbb71fd7fa167a5d');
    const t = await tree(7);

    expect(hex(await t.root())).toBe(hex(root7));
    expect((await t.inclusionProof(3, 7)).map(hex)).toEqual([a, b, c].map(hex));
    expect((await t.consistencyProof(3, 7)).map(hex)).toEqual([a, d, b, c].map(hex));
    expect(await verifyInclusion(3, 7, await leafHash(Uint8Array.of(3)), [a, b, c], root7)).toBe(
      true,
    );
    expect(await verifyConsistency(3, 7, await t.root(3), root7, [a, d, b, c])).toBe(true);
  });

  it('accepts every honest proof and rejects every wrong one, up to 17 leaves', async () => {
    const big = await tree(17);
    const roots = await Promise.all(Array.from({ length: 18 }, (_, n) => big.root(n)));
    for (let size = 1; size <= 17; size++) {
      for (let i = 0; i < size; i++) {
        const proof = await big.inclusionProof(i, size);
        expect(await verifyInclusion(i, size, big.leaves[i], proof, roots[size])).toBe(true);
        expect(await verifyInclusion(i, size, big.leaves[(i + 1) % 17], proof, roots[size])).toBe(
          false,
        );
        if (size > 1) {
          expect(await verifyInclusion(i, size, big.leaves[i], proof, roots[size - 1])).toBe(false);
        }
      }
      for (let old = 1; old <= size; old++) {
        const proof = await big.consistencyProof(old, size);
        expect(await verifyConsistency(old, size, roots[old], roots[size], proof)).toBe(true);
        if (old < size) {
          expect(await verifyConsistency(old, size, roots[old - 1], roots[size], proof)).toBe(
            false,
          );
          expect(await verifyConsistency(old, size, roots[old], roots[size - 1], proof)).toBe(
            false,
          );
        }
      }
    }
  });

  it('rejects a consistency proof over a rewritten history', async () => {
    const honest = await tree(9);
    const leaves = Array.from({ length: 12 }, (_, i) => Uint8Array.of(i === 4 ? 99 : i));
    const rewritten = await TestMerkleTree.of(leaves);

    const proof = await rewritten.consistencyProof(9, 12);

    expect(await verifyConsistency(9, 12, await honest.root(), await rewritten.root(), proof)).toBe(
      false,
    );
  });

  it('rejects malformed sizes and indexes', async () => {
    const t = await tree(4);
    const root = await t.root();
    expect(await verifyInclusion(-1, 4, t.leaves[0], [], root)).toBe(false);
    expect(await verifyInclusion(4, 4, t.leaves[0], [], root)).toBe(false);
    expect(await verifyInclusion(0.5, 4, t.leaves[0], [], root)).toBe(false);
    expect(await verifyConsistency(0, 4, root, root, [])).toBe(false);
    expect(await verifyConsistency(5, 4, root, root, [])).toBe(false);
    expect(await verifyConsistency(4, 4, root, root, [root])).toBe(false);
  });
});
