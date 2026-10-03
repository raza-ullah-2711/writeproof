import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { fromBase64Url } from '../crypto/base64url';
import { Checkpoint, verifyCheckpoint } from './checkpoint';
import { KeyRotation, followRotations } from './key-rotation';
import { LedgerTrustStore } from './ledger-trust';
import { LedgerEntry, entryHash } from './ledger-verify';
import { leafHash, verifyConsistency, verifyInclusion } from './merkle';
import { AnchorProof, PUBLIC_LOG, PublicLog, verifyAnchor } from './rekor';

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
  private readonly log = inject(PUBLIC_LOG);

  /**
   * Proves that `payloadHash` (a letter's hash) is entry `ref.seq` of the ledger without trusting the server or reading the whole ledger:
   * an O(log n) inclusion proof against a checkpoint signed by the pinned ledger key (or a key it
   * handed over to, through each handover's checkpoint), plus an
   * O(log n) consistency proof that this checkpoint extends the last one this browser verified,
   * so history can't have been rewritten in between.
   */
  async verifyEntry(
    ref: Pick<LedgerEntry, 'seq' | 'entryHash'>,
    payloadHash: string,
  ): Promise<LedgerCheck> {
    const fail = (problem: string) => ({ problem, size: 0 });
    try {
      const { publicKey, rotations = [] } = await firstValueFrom(
        this.http.get<{ publicKey: string; rotations?: KeyRotation[] }>('/api/ledger/key'),
      );
      const trusted = this.trust.load();
      // A new key is trusted only through handovers signed by the key this browser pinned.
      let handovers: KeyRotation[] = [];
      if (trusted && trusted.publicKey !== publicKey) {
        const path = await followRotations(trusted.publicKey, publicKey, rotations);
        if ('problem' in path) {
          return fail(path.problem);
        }
        handovers = path.rotations;
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
        // From the last checkpoint seen, through each checkpoint an old key handed over at, to now.
        let from: Pick<Checkpoint, 'size' | 'root'> = trusted;
        for (const next of [...handovers, checkpoint]) {
          const problem = await this.checkExtends(from.size, from.root, next);
          if (problem) {
            return fail(problem);
          }
          from = next;
        }
      }
      if (this.log) {
        const keys = [publicKey, ...rotations.map((r) => r.oldKey)];
        const problem = await this.checkAnchored(this.log, keys, checkpoint);
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

  /**
   * Null if what this browser has seen is, or may still become, anchored in the public log. A
   * checkpoint it saw more than `log.graceMillis` ago must by now be covered by an anchor it
   * verifies itself. A server showing this browser a history nobody else sees must then anchor that
   * history publicly, where witnesses find it next to the real one (docs/ledger.md).
   */
  private async checkAnchored(
    log: PublicLog,
    keys: string[],
    checkpoint: Pick<Checkpoint, 'size' | 'root'>,
  ): Promise<string | null> {
    const now = Date.now();
    const state = this.trust.anchoring();
    let pending =
      state.pending ??
      (checkpoint.size > state.anchoredSize
        ? { size: checkpoint.size, root: checkpoint.root, firstSeen: now }
        : null);
    let anchoredSize = state.anchoredSize;
    if (pending && now - pending.firstSeen > log.graceMillis) {
      const stale = `What this browser saw of the ledger on ${new Date(pending.firstSeen).toUTCString()} is still not anchored in the public log, so it can't rule out being shown a different history than everyone else`;
      let anchor: AnchorProof;
      try {
        anchor = await firstValueFrom(this.http.get<AnchorProof>('/api/ledger/anchor/latest'));
      } catch {
        return stale;
      }
      let problem: string | null = 'The anchor is not signed by any ledger key';
      for (const key of keys) {
        problem = await verifyAnchor(anchor, log, key);
        if (!problem) {
          break;
        }
      }
      if (problem) {
        return problem;
      }
      if (anchor.checkpoint.size < pending.size) {
        return stale;
      }
      if (await this.checkExtends(pending.size, pending.root, anchor.checkpoint)) {
        return "The ledger anchored in the public log doesn't contain what this browser was shown: it was shown a different history";
      }
      anchoredSize = anchor.checkpoint.size;
      pending =
        checkpoint.size > anchoredSize
          ? { size: checkpoint.size, root: checkpoint.root, firstSeen: now }
          : null;
    }
    this.trust.saveAnchoring({ pending, anchoredSize });
    return null;
  }

  /** Null if `checkpoint` provably extends the ledger at `size` entries with `root`. */
  private async checkExtends(
    size: number,
    root: string,
    checkpoint: Pick<Checkpoint, 'size' | 'root'>,
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
