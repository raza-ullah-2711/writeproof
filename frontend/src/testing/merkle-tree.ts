import { leafHash, nodeHash } from '../app/letters/merkle';

type Bytes = Uint8Array<ArrayBuffer>;

/** Builds RFC 9162 trees and proofs, playing the server in tests. Mirrors backend `MerkleTree`. */
export class TestMerkleTree {
  private constructor(readonly leaves: Bytes[]) {}

  static async of(data: Bytes[]): Promise<TestMerkleTree> {
    return new TestMerkleTree(await Promise.all(data.map(leafHash)));
  }

  async root(size = this.leaves.length): Promise<Bytes> {
    return size === 0
      ? new Uint8Array(await crypto.subtle.digest('SHA-256', new Uint8Array()))
      : this.subtree(0, size);
  }

  async inclusionProof(index: number, size: number): Promise<Bytes[]> {
    const out: Bytes[] = [];
    await this.path(index, 0, size, out);
    return out;
  }

  async consistencyProof(oldSize: number, newSize: number): Promise<Bytes[]> {
    const out: Bytes[] = [];
    if (oldSize < newSize) {
      await this.subproof(oldSize, 0, newSize, true, out);
    }
    return out;
  }

  private async subtree(from: number, to: number): Promise<Bytes> {
    if (to - from === 1) {
      return this.leaves[from];
    }
    const k = split(to - from);
    return nodeHash(await this.subtree(from, from + k), await this.subtree(from + k, to));
  }

  private async path(m: number, from: number, to: number, out: Bytes[]): Promise<void> {
    const n = to - from;
    if (n === 1) {
      return;
    }
    const k = split(n);
    if (m < k) {
      await this.path(m, from, from + k, out);
      out.push(await this.subtree(from + k, to));
    } else {
      await this.path(m - k, from + k, to, out);
      out.push(await this.subtree(from, from + k));
    }
  }

  private async subproof(m: number, from: number, to: number, complete: boolean, out: Bytes[]) {
    const n = to - from;
    if (m === n) {
      if (!complete) {
        out.push(await this.subtree(from, to));
      }
      return;
    }
    const k = split(n);
    if (m <= k) {
      await this.subproof(m, from, from + k, complete, out);
      out.push(await this.subtree(from + k, to));
    } else {
      await this.subproof(m - k, from + k, to, false, out);
      out.push(await this.subtree(from, from + k));
    }
  }
}

/** Largest power of two strictly less than n (n >= 2). */
function split(n: number): number {
  let k = 1;
  while (k * 2 < n) {
    k *= 2;
  }
  return k;
}
