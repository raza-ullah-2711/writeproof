import { toBase64Url } from '../crypto/base64url';
import { HandwritingSample } from '../handwriting/handwriting-sample';
import { handwritingHash } from '../letters/letter-format';
import { openLetterHashV2 } from '../open-letters/open-letter-format';
import {
  Page,
  inkHash,
  pageFromSample,
  pageHash,
  pageStrokes,
  pagesHash,
  timingHash,
} from './page-format';

const VECTOR: Page = {
  format: 'writeproof.page',
  version: 1,
  capturedAt: '2026-10-13T09:12:44.120Z',
  device: 'pen',
  pen: { nib: 'fountain', ink: 'ink' },
  ink: [
    [100, 200, 128, 5, -3, 2, 4, 1, -1],
    [300, 400, 90],
  ],
  timing: [[0, 8, 9], [120]],
};

describe('page format (shared vector with backend HandwrittenPageTest)', () => {
  it('hashes the ink, the timing, the page, the pages and the v2 letter', async () => {
    const ink = await inkHash(VECTOR);
    const timing = await timingHash(VECTOR);
    const page = await pageHash(ink, timing);
    const pages = await pagesHash(true, [page]);
    const author = toBase64Url(Uint8Array.from({ length: 32 }, (_, i) => i + 1));
    const hw = await handwritingHash('{"format":"writeproof.handwriting"}');

    expect(ink).toBe('ycKsmUVD1_5sd6QCktQe8dELzGq9iLd5_cwRrjLyCfI');
    expect(timing).toBe('c0buoYxwrOrV1dvcEWLK4WGKg0wqL4JmWkn8CJ5W0Fw');
    expect(page).toBe('hPrNA0307ki92W8crTC5kUAxoRslspn54WAb5vrfNt8');
    expect(pages).toBe('74nMQGqiddyXs7Vun80MoozcrbH2xJRkttZ-oa49eko');
    expect(
      toBase64Url(
        await openLetterHashV2(author, '2026-10-13T09:20:00.000Z', hw, 'Transcript ✍', pages),
      ),
    ).toBe('jEFmyDrqqVWkPoh61ma9QPlME2oiBksqQ0XrzPivILk');
  });

  it('commits to the replay choice and the order of pages', async () => {
    expect(await pagesHash(true, ['a', 'b'])).not.toBe(await pagesHash(false, ['a', 'b']));
    expect(await pagesHash(true, ['a', 'b'])).not.toBe(await pagesHash(true, ['b', 'a']));
  });

  it('encodes a pad sample as integer deltas and decodes it back', () => {
    const sample: HandwritingSample = {
      format: 'writeproof.handwriting',
      version: 1,
      capturedAt: '2026-10-13T09:12:44.120Z',
      device: 'touch',
      width: 1000,
      height: 1414,
      strokes: [
        [
          { x: 10.04, y: 20.0, t: 0, pressure: 0.5, penDown: true },
          { x: 10.55, y: 19.71, t: 8.04, pressure: 0.508, penDown: true },
          { x: 10.9, y: 19.8, t: 16.6, pressure: 0, penDown: false },
        ],
      ],
    };
    const page = pageFromSample(sample, { nib: 'brush', ink: 'sepia' });

    expect(page.ink).toEqual([[100, 200, 128, 6, -3, 2, 3, 1, -130]]);
    expect(page.timing).toEqual([[0, 8, 9]]);
    const back = pageStrokes(page)[0];
    expect(back.map((p) => [p.x, p.y, p.t, p.penDown])).toEqual([
      [10, 20, 0, true],
      [10.6, 19.7, 8, true],
      [10.9, 19.8, 17, false],
    ]);
  });

  it('draws a page without timing but cannot hash timing it does not have', async () => {
    const { timing, capturedAt, ...inkOnly } = VECTOR;
    expect(timing && capturedAt).toBeTruthy();
    expect(
      pageStrokes(inkOnly)
        .flat()
        .every((p) => p.t === 0),
    ).toBe(true);
    await expect(timingHash(inkOnly as Page)).rejects.toThrow('no timing');
  });
});
