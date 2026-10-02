import { Injectable } from '@angular/core';

/** What this browser has verified about the ledger before: the key it pinned and a checkpoint. */
export interface LedgerTrust {
  publicKey: string;
  size: number;
  root: string;
}

const STORAGE_KEY = 'writeproof.ledger-trust.v1';

/**
 * Remembers the ledger key on first use and the largest checkpoint verified since, so every later
 * check can demand proof that the ledger only grew. Not secret, so localStorage is fine; if it is
 * unavailable, each check stands alone (the key is then trusted afresh every time).
 */
@Injectable({ providedIn: 'root' })
export class LedgerTrustStore {
  load(): LedgerTrust | null {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) {
        return null;
      }
      const t = JSON.parse(raw) as Partial<LedgerTrust>;
      return typeof t.publicKey === 'string' &&
        typeof t.root === 'string' &&
        Number.isSafeInteger(t.size)
        ? { publicKey: t.publicKey, size: t.size!, root: t.root }
        : null;
    } catch {
      return null;
    }
  }

  /** Saves `next` unless an equal or larger checkpoint is already remembered. */
  remember(next: LedgerTrust): void {
    const current = this.load();
    if (current && current.publicKey === next.publicKey && current.size >= next.size) {
      return;
    }
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } catch {
      // Storage full or blocked: the next check simply starts from scratch.
    }
  }
}
