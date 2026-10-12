import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { nextRequest } from '../../testing/http';
import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { verifyEd25519 } from '../crypto/ed25519';
import { HandwritingSample } from '../handwriting/handwriting-sample';
import { LedgerVerifier } from '../letters/ledger-verifier';
import { handwritingHash, letterSignedMessage } from '../letters/letter-format';
import { WalletService } from '../wallet/wallet.service';
import { openLetterHash, openLetterHashV2 } from './open-letter-format';
import { Page, inkHash, pageHash, pagesHash, timingHash } from '../pages/page-format';
import { OpenLetter, OpenLettersService } from './open-letters.service';

const SIGNATURE: HandwritingSample = {
  format: 'writeproof.handwriting',
  version: 1,
  capturedAt: '2026-10-02T12:00:00.000Z',
  device: 'pen',
  width: 600,
  height: 240,
  strokes: [
    [
      { x: 10, y: 20, t: 0, pressure: 0.4, penDown: true },
      { x: 90, y: 30, t: 420, pressure: 0, penDown: false },
    ],
  ],
};

describe('OpenLettersService', () => {
  let service: OpenLettersService;
  let wallet: WalletService;
  let http: HttpTestingController;
  let verifyEntry: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    globalThis.indexedDB = new IDBFactory();
    verifyEntry = vi.fn().mockResolvedValue({ problem: null, size: 12 });
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: LedgerVerifier, useValue: { verifyEntry } },
      ],
    });
    service = TestBed.inject(OpenLettersService);
    wallet = TestBed.inject(WalletService);
    http = TestBed.inject(HttpTestingController);
    await wallet.create();
  });

  afterEach(() => http.verify());

  /** Publishes as this wallet, playing the server; returns what the server stored. */
  async function publish(body: string): Promise<OpenLetter> {
    const publishing = service.publish(body, SIGNATURE);
    const post = await nextRequest(http, '/api/me/open-letters');
    const { sentAt, signature, handwriting } = post.request.body;
    const hw = await handwritingHash(handwriting);
    const hash = toBase64Url(await openLetterHash(wallet.publicKey()!, sentAt, hw, body));
    const stored: OpenLetter = {
      letterHash: hash,
      author: wallet.publicKey()!,
      sentAt,
      body: post.request.body.body,
      signature,
      handwritingHash: hw,
      handwritingScore: 0.87,
      ledger: { seq: 4, prevHash: '', payloadHash: hash, recordedAtMillis: 0, entryHash: 'e4' },
      removed: null,
    };
    post.flush(stored);
    await publishing;
    return stored;
  }

  it('signs exactly the text, time and handwriting, and sends the strokes only for verification', async () => {
    const letter = await publish('To everyone:\nI wrote this.');

    expect(
      await verifyEd25519(
        wallet.publicKey()!,
        letterSignedMessage(fromBase64Url(letter.letterHash)),
        letter.signature,
      ),
    ).toBe(true);
    expect(letter.body).toBe('To everyone:\nI wrote this.');
  });

  it('verifies a letter against its link, its signature and the ledger', async () => {
    const letter = await publish('Hello, world');

    await expect(service.verify(letter, letter.letterHash)).resolves.toEqual({
      signatureValid: true,
      ledgerValid: true,
      ledgerProblem: null,
      ledgerCheckpointSize: 12,
    });
    expect(verifyEntry).toHaveBeenCalledWith(letter.ledger, letter.letterHash);
  });

  it('catches a server that changes the text or answers a link with another letter', async () => {
    const letter = await publish('I promise to pay 10');
    const other = await publish('Something else entirely');

    expect(
      (await service.verify({ ...letter, body: 'I promise to pay 1000' }, letter.letterHash))
        .signatureValid,
    ).toBe(false);
    expect((await service.verify(other, letter.letterHash)).signatureValid).toBe(false);
    expect(
      (await service.verify({ ...letter, sentAt: '2020-01-01T00:00:00.000Z' }, letter.letterHash))
        .signatureValid,
    ).toBe(false);
  });

  it('reports a ledger problem', async () => {
    const letter = await publish('Hi');
    verifyEntry.mockResolvedValue({ problem: 'The ledger was rewritten', size: 0 });

    await expect(service.verify(letter, letter.letterHash)).resolves.toMatchObject({
      signatureValid: true,
      ledgerValid: false,
      ledgerProblem: 'The ledger was rewritten',
      ledgerCheckpointSize: null,
    });
  });

  it('refuses empty or oversized letters before contacting the server', async () => {
    await expect(service.publish('  ', SIGNATURE)).rejects.toThrow(/1 to 10000/);
    await expect(service.publish('x'.repeat(10_001), SIGNATURE)).rejects.toThrow(/1 to 10000/);
  });

  it('refuses a server that records a different letter', async () => {
    const publishing = service.publish('Mine', SIGNATURE);
    (await nextRequest(http, '/api/me/open-letters')).flush({ letterHash: 'something-else' });

    await expect(publishing).rejects.toThrow(/different letter hash/);
  });

  it('checks only the ledger record of a removed letter', async () => {
    const letter = await publish('Taken down later');

    const checked = await service.verify(
      { ...letter, body: null, removed: { category: 'spam', at: '' } },
      letter.letterHash,
    );

    expect(checked).toEqual({
      signatureValid: false,
      ledgerValid: true,
      ledgerProblem: null,
      ledgerCheckpointSize: 12,
    });
    expect(verifyEntry).toHaveBeenCalledWith(letter.ledger, letter.letterHash);
  });

  it('files a report, with or without a note', async () => {
    const first = service.report('hash/1', 'spam', '  ');
    const req = http.expectOne('/api/open-letters/hash%2F1/reports');
    expect(req.request.body).toEqual({ category: 'spam' });
    req.flush(null, { status: 202, statusText: 'Accepted' });
    await first;

    const second = service.report('h2', 'other', ' Looks fake ');
    expect(http.expectOne('/api/open-letters/h2/reports').request.body).toEqual({
      category: 'other',
      note: 'Looks fake',
    });
  });

  describe('handwritten pages (v2)', () => {
    const PAGE: Page = {
      format: 'writeproof.page',
      version: 1,
      capturedAt: '2026-10-13T09:12:44.120Z',
      device: 'pen',
      pen: { nib: 'fountain', ink: 'sepia' },
      ink: [
        [1000, 2000, 128, 50, -30, 2, 40, 10, -1],
        [3000, 4000, 90],
      ],
      timing: [[0, 8, 9], [120]],
    };

    /** Publishes pages as this wallet, playing the server; returns the letter as published. */
    async function publishPages(transcript: string, replay: boolean): Promise<OpenLetter> {
      const publishing = service.publish(transcript, SIGNATURE, { pages: [PAGE], replay });
      const post = await nextRequest(http, '/api/me/open-letters');
      const sent = post.request.body;
      expect(sent.pages).toEqual([PAGE]);
      expect(sent.replay).toBe(replay);
      const hw = await handwritingHash(sent.handwriting);
      const th = await timingHash(PAGE);
      const pages = await pagesHash(replay, [await pageHash(await inkHash(PAGE), th)]);
      const hash = toBase64Url(
        await openLetterHashV2(wallet.publicKey()!, sent.sentAt, hw, transcript, pages),
      );
      const { timing, capturedAt, ...inkOnly } = PAGE;
      const stored: OpenLetter = {
        letterHash: hash,
        author: wallet.publicKey()!,
        sentAt: sent.sentAt,
        body: transcript,
        signature: sent.signature,
        handwritingHash: hw,
        handwritingScore: 0.9,
        ledger: { seq: 9, prevHash: '', payloadHash: hash, recordedAtMillis: 0, entryHash: 'e9' },
        removed: null,
        version: 2,
        replay,
        pages: [replay ? { ...PAGE, timingHash: th } : { ...inkOnly, timingHash: th }],
      };
      expect(timing && capturedAt).toBeTruthy();
      post.flush(stored);
      await publishing;
      return stored;
    }

    it('signs the pages and the replay choice, and readers can check them without the timing', async () => {
      const letter = await publishPages('', false);

      expect(letter.pages![0].timing).toBeUndefined();
      expect((await service.verify(letter, letter.letterHash)).signatureValid).toBe(true);
    });

    it('checks published timing against its hash when the author allowed replay', async () => {
      const letter = await publishPages('Dear Grandma', true);

      expect((await service.verify(letter, letter.letterHash)).signatureValid).toBe(true);
      const retimed = { ...letter.pages![0], timing: [[0, 8, 10], [120]] };
      expect(
        (await service.verify({ ...letter, pages: [retimed] }, letter.letterHash)).signatureValid,
      ).toBe(false);
    });

    it('fails on changed ink, a flipped replay flag or timing the author kept private', async () => {
      const letter = await publishPages('', false);
      const page = letter.pages![0];

      const inked = {
        ...page,
        ink: [
          [1000, 2000, 128, 50, -30, 2, 40, 11, -1],
          [3000, 4000, 90],
        ],
      };
      expect(
        (await service.verify({ ...letter, pages: [inked] }, letter.letterHash)).signatureValid,
      ).toBe(false);
      expect(
        (await service.verify({ ...letter, replay: true }, letter.letterHash)).signatureValid,
      ).toBe(false);
      const leaked = { ...page, timing: PAGE.timing, capturedAt: PAGE.capturedAt };
      expect(
        (await service.verify({ ...letter, pages: [leaked] }, letter.letterHash)).signatureValid,
      ).toBe(false);
      expect(
        (await service.verify({ ...letter, body: 'added' }, letter.letterHash)).signatureValid,
      ).toBe(false);
    });

    it('refuses more than four pages before signing anything', async () => {
      await expect(
        service.publish('', SIGNATURE, { pages: [PAGE, PAGE, PAGE, PAGE, PAGE], replay: false }),
      ).rejects.toThrow('at most 4 pages');
    });
  });
});
