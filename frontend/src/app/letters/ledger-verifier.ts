import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { fromBase64Url } from '../crypto/base64url';
import { Checkpoint, verifyCheckpoint } from './checkpoint';
import { LedgerTrustStore } from './ledger-trust';
import { LedgerEntry, entryHash } from './ledger-verify';
import { leafHash, verifyConsistency, verifyInclusion } from './merkle';

/** Null `problem` when the proof held; `size` is the signed checkpoint it was proven against. */
export interface LedgerCheck {
  problem: string | null;
  size: number;
}

interface InclusionProof {
  checkpoint: Checkpoint;
  entry: LedgerEntry;
  proof: string[];
}

/** Checks ledger proofs for sealed and open letters alike. See docs/ledger.md. */
@Injectable({ providedIn: 'root' })
export class LedgerVerifier {
  private readonly http = inject(HttpClient);
  private readonly trust = inject(LedgerTrustStore);

  /**
   * Proves that `payloadHash` (a letter's hash) is entry `ref.seq` of the ledger without trusting the server or reading the whole ledger:
   * an O(log n) inclusion proof against a checkpoint signed by the pinned ledger key, plus an
   * O(log n) consistency proof that this checkpoint extends the last one this browser verified,
   * so history can't have been rewritten in between.
   */
  async verifyEntry(
    ref: Pick<LedgerEntry, 'seq' | 'entryHash'>,
    payloadHash: string,
  ): Promise<LedgerCheck> {
    const fail = (problem: string) => ({ problem, size: 0 });
    try {
      const { publicKey } = await firstValueFrom(
        this.http.get<{ publicKey: string }>('/api/ledger/key'),
      );
      const trusted = this.trust.load();
      if (trusted && trusted.publicKey !== publicKey) {
        return fail(
          'The ledger key changed since this browser last checked, so nothing it signs can be trusted',
        );
      }
      const seq = ref.seq;
      const { checkpoint, entry, proof } = await firstValueFrom(
        this.http.get<InclusionProof>('/api/ledger/proof/inclusion', { params: { seq } }),
      );
      if (!(await verifyCheckpoint(publicKey, checkpoint))) {
        return fail('The ledger checkpoint is not signed by the ledger key');
      }
      const recomputed = await entryHash(
        entry.seq,
        entry.prevHash,
        entry.payloadHash,
        entry.recordedAtMillis,
      );
      if (entry.seq !== seq || recomputed !== entry.entryHash) {
        return fail('The ledger returned a malformed entry for this letter');
      }
      if (entry.payloadHash !== payloadHash || entry.entryHash !== ref.entryHash) {
        return fail("The ledger entry doesn't commit to this letter");
      }
      const included = await verifyInclusion(
        seq - 1,
        checkpoint.size,
        await leafHash(fromBase64Url(entry.entryHash)),
        proof.map(fromBase64Url),
        fromBase64Url(checkpoint.root),
      );
      if (!included) {
        return fail(`Entry #${seq} is not in the signed checkpoint`);
      }
      if (trusted) {
        const problem = await this.checkExtends(trusted.size, trusted.root, checkpoint);
        if (problem) {
          return fail(problem);
        }
      }
      this.trust.remember({ publicKey, size: checkpoint.size, root: checkpoint.root });
      return { problem: null, size: checkpoint.size };
    } catch {
      return fail("The ledger's proofs could not be fetched or read");
    }
  }

  /** Null if `checkpoint` provably extends the ledger at `size` entries with `root`. */
  private async checkExtends(
    size: number,
    root: string,
    checkpoint: Checkpoint,
  ): Promise<string | null> {
    if (checkpoint.size < size) {
      return `The ledger shrank from ${size} to ${checkpoint.size} entries since this browser last checked`;
    }
    let proof: string[] = [];
    if (checkpoint.size > size && size > 0) {
      proof = (
        await firstValueFrom(
          this.http.get<{ proof: string[] }>('/api/ledger/proof/consistency', {
            params: { from: size, to: checkpoint.size },
          }),
        )
      ).proof;
    }
    const consistent =
      size === 0 ||
      (await verifyConsistency(
        size,
        checkpoint.size,
        fromBase64Url(root),
        fromBase64Url(checkpoint.root),
        proof.map(fromBase64Url),
      ));
    return consistent
      ? null
      : 'The ledger was rewritten since this browser last checked: it does not extend the checkpoint seen before';
  }
}
