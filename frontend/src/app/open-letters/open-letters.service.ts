import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { verifyEd25519 } from '../crypto/ed25519';
import { HandwritingSample, toJson } from '../handwriting/handwriting-sample';
import { handwritingHash, letterSignedMessage } from '../letters/letter-format';
import { LedgerVerifier } from '../letters/ledger-verifier';
import { LedgerEntry } from '../letters/ledger-verify';
import { WalletService } from '../wallet/wallet.service';
import { openLetterHash, openLetterHashV2 } from './open-letter-format';
import {
  MAX_PAGES,
  MAX_PAGE_POINTS,
  Page,
  inkHash,
  ownPageHash,
  pageHash,
  pagePointCount,
  pagesHash,
  timingHash,
} from '../pages/page-format';
import { ReportCategory } from './report-categories';

/** An open letter as the server returns it. Nothing here is trusted until `verify`. */
export interface OpenLetter {
  letterHash: string;
  /** The author's address (public key). */
  author: string;
  sentAt: string;
  /** Null once Writeproof took the letter down (see `removed`). */
  body: string | null;
  signature: string;
  /** Hash of the handwritten signature; the strokes themselves are never published. */
  handwritingHash: string;
  /** Similarity Writeproof measured when it was published. */
  handwritingScore: number;
  ledger: LedgerEntry;
  /**
   * Set when a moderator took the letter down (or its author withdrew it); the record and its
   * ledger entry remain. While `appealUntil` is set the text still exists, hidden, and the author
   * may appeal until then; `appealed` once they have.
   */
  removed: { category: string; at: string; appealUntil?: string | null; appealed?: boolean } | null;
  /** 1: typed. 2: handwritten pages, with `body` as the optional transcript. */
  version?: 1 | 2;
  /** The author let readers watch the pages being written (they then carry their timing). */
  replay?: boolean;
  /** Handwritten pages (v2); null once removed. */
  pages?: Page[] | null;
}

/** Handwritten pages to publish with a letter (docs/handwritten-pages.md). */
export interface HandwrittenPages {
  pages: Page[];
  replay: boolean;
}

/** What the reader's own browser established. */
export interface VerifiedOpenLetter {
  /** The hash recomputes from what is shown, and the author's wallet signed it. */
  signatureValid: boolean;
  ledgerValid: boolean;
  ledgerProblem: string | null;
  ledgerCheckpointSize: number | null;
}

export const MAX_OPEN_LETTER_LENGTH = 10_000;

/**
 * Open letters: signed by hand and wallet and recorded on the ledger, but not sealed. Anyone with
 * the link can read one and check it for themselves, without an account.
 */
@Injectable({ providedIn: 'root' })
export class OpenLettersService {
  private readonly http = inject(HttpClient);
  private readonly wallet = inject(WalletService);
  private readonly ledger = inject(LedgerVerifier);

  /**
   * Publishes a typed letter, or with `handwritten` a letter of handwritten pages whose `body`
   * is the optional transcript. The wallet signs the pages and the replay choice too.
   */
  async publish(
    body: string,
    signature: HandwritingSample,
    handwritten?: HandwrittenPages,
  ): Promise<OpenLetter> {
    const me = this.wallet.publicKey();
    if (!me) {
      throw new Error('No wallet is loaded');
    }
    const pages = handwritten?.pages ?? [];
    if (body.length > MAX_OPEN_LETTER_LENGTH || (pages.length === 0 && !body.trim())) {
      throw new Error(`An open letter needs 1 to ${MAX_OPEN_LETTER_LENGTH} characters`);
    }
    if (pages.length > MAX_PAGES) {
      throw new Error(`An open letter can have at most ${MAX_PAGES} pages`);
    }
    if (pages.some((p) => pagePointCount(p) > MAX_PAGE_POINTS)) {
      throw new Error('A page has too much writing; split it over two pages');
    }
    const replay = pages.length > 0 && !!handwritten?.replay;
    const handwriting = toJson(signature);
    const sentAt = new Date().toISOString();
    const hw = await handwritingHash(handwriting);
    const hash =
      pages.length === 0
        ? await openLetterHash(me, sentAt, hw, body)
        : await openLetterHashV2(
            me,
            sentAt,
            hw,
            body,
            await pagesHash(replay, await Promise.all(pages.map(ownPageHash))),
          );
    const walletSignature = await this.wallet.sign(letterSignedMessage(hash));
    const letter = await firstValueFrom(
      this.http.post<OpenLetter>('/api/me/open-letters', {
        sentAt,
        body,
        signature: toBase64Url(walletSignature),
        handwriting,
        ...(pages.length > 0 ? { pages, replay } : {}),
      }),
    );
    if (letter.letterHash !== toBase64Url(hash)) {
      throw new Error('The server recorded a different letter hash');
    }
    return letter;
  }

  get(letterHash: string): Promise<OpenLetter> {
    return firstValueFrom(
      this.http.get<OpenLetter>(`/api/open-letters/${encodeURIComponent(letterHash)}`),
    );
  }

  /** Reports a letter to the moderators; works signed out too. */
  report(letterHash: string, category: ReportCategory, note: string): Promise<void> {
    return firstValueFrom(
      this.http.post<void>(`/api/open-letters/${encodeURIComponent(letterHash)}/reports`, {
        category,
        ...(note.trim() ? { note: note.trim() } : {}),
      }),
    );
  }

  /** Appeals a takedown of one of your letters while it can still be restored. */
  appeal(letterHash: string, text: string): Promise<void> {
    return firstValueFrom(
      this.http.post<void>(`/api/me/open-letters/${letterHash}/appeal`, { text: text.trim() }),
    );
  }

  mine(): Promise<OpenLetter[]> {
    return firstValueFrom(this.http.get<OpenLetter[]>('/api/me/open-letters'));
  }

  /**
   * Checks an open letter without trusting the server: the hash recomputes from the author, time,
   * handwriting hash and the exact body shown; the author's key signed it; and it is provably in
   * the ledger. `expectedHash` is the hash from the link, so a server can't swap in another letter.
   */
  async verify(letter: OpenLetter, expectedHash: string): Promise<VerifiedOpenLetter> {
    if (letter.body === null) {
      // Removed: the text can't be checked any more, but the record is still provably on the
      // ledger under the hash in the link.
      const ledger = await this.ledger.verifyEntry(letter.ledger, expectedHash);
      return {
        signatureValid: false,
        ledgerValid: ledger.problem === null,
        ledgerProblem: ledger.problem,
        ledgerCheckpointSize: ledger.problem === null ? ledger.size : null,
      };
    }
    const hash = await this.recomputeHash(letter, letter.body);
    const signatureValid =
      hash !== null &&
      hash === expectedHash &&
      hash === letter.letterHash &&
      (await verifyEd25519(
        letter.author,
        letterSignedMessage(fromBase64Url(hash)),
        letter.signature,
      ));
    const ledger = await this.ledger.verifyEntry(letter.ledger, hash ?? expectedHash);
    return {
      signatureValid,
      ledgerValid: ledger.problem === null,
      ledgerProblem: ledger.problem,
      ledgerCheckpointSize: ledger.problem === null ? ledger.size : null,
    };
  }

  /**
   * The letter hash from what is shown, or null if the pages can't match any signed letter (e.g.
   * timing published although the author didn't allow replay, or a page's timing that doesn't
   * match its hash).
   */
  private async recomputeHash(letter: OpenLetter, body: string): Promise<string | null> {
    if ((letter.version ?? 1) === 1) {
      return toBase64Url(
        await openLetterHash(letter.author, letter.sentAt, letter.handwritingHash, body),
      );
    }
    const replay = !!letter.replay;
    const pages = letter.pages ?? [];
    if (pages.length === 0 || pages.length > MAX_PAGES) {
      return null;
    }
    const hashes: string[] = [];
    for (const page of pages) {
      if (!page.timingHash || !!page.timing !== replay) {
        return null;
      }
      if (replay && (await timingHash(page)) !== page.timingHash) {
        return null;
      }
      hashes.push(await pageHash(await inkHash(page), page.timingHash));
    }
    return toBase64Url(
      await openLetterHashV2(
        letter.author,
        letter.sentAt,
        letter.handwritingHash,
        body,
        await pagesHash(replay, hashes),
      ),
    );
  }
}
