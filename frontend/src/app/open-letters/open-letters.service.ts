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
import { openLetterHash } from './open-letter-format';

/** An open letter as the server returns it. Nothing here is trusted until `verify`. */
export interface OpenLetter {
  letterHash: string;
  /** The author's address (public key). */
  author: string;
  sentAt: string;
  body: string;
  signature: string;
  /** Hash of the handwritten signature; the strokes themselves are never published. */
  handwritingHash: string;
  /** Similarity Writeproof measured when it was published. */
  handwritingScore: number;
  ledger: LedgerEntry;
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

  async publish(body: string, signature: HandwritingSample): Promise<OpenLetter> {
    const me = this.wallet.publicKey();
    if (!me) {
      throw new Error('No wallet is loaded');
    }
    if (body.trim().length === 0 || body.length > MAX_OPEN_LETTER_LENGTH) {
      throw new Error(`An open letter needs 1 to ${MAX_OPEN_LETTER_LENGTH} characters`);
    }
    const handwriting = toJson(signature);
    const sentAt = new Date().toISOString();
    const hash = await openLetterHash(me, sentAt, await handwritingHash(handwriting), body);
    const walletSignature = await this.wallet.sign(letterSignedMessage(hash));
    const letter = await firstValueFrom(
      this.http.post<OpenLetter>('/api/me/open-letters', {
        sentAt,
        body,
        signature: toBase64Url(walletSignature),
        handwriting,
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

  mine(): Promise<OpenLetter[]> {
    return firstValueFrom(this.http.get<OpenLetter[]>('/api/me/open-letters'));
  }

  /**
   * Checks an open letter without trusting the server: the hash recomputes from the author, time,
   * handwriting hash and the exact body shown; the author's key signed it; and it is provably in
   * the ledger. `expectedHash` is the hash from the link, so a server can't swap in another letter.
   */
  async verify(letter: OpenLetter, expectedHash: string): Promise<VerifiedOpenLetter> {
    const hash = toBase64Url(
      await openLetterHash(letter.author, letter.sentAt, letter.handwritingHash, letter.body),
    );
    const signatureValid =
      hash === expectedHash &&
      hash === letter.letterHash &&
      (await verifyEd25519(
        letter.author,
        letterSignedMessage(fromBase64Url(hash)),
        letter.signature,
      ));
    const ledger = await this.ledger.verifyEntry(letter.ledger, hash);
    return {
      signatureValid,
      ledgerValid: ledger.problem === null,
      ledgerProblem: ledger.problem,
      ledgerCheckpointSize: ledger.problem === null ? ledger.size : null,
    };
  }
}
