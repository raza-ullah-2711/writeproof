import { toBase64Url } from '../crypto/base64url';
import { HandwritingSample, InputDevice, Stroke } from '../handwriting/handwriting-sample';
import { sha256 } from '../letters/letter-format';

/**
 * A handwritten page (`writeproof.page` v1, docs/handwritten-pages.md): stroke dynamics on a
 * 1000 × 1414 sheet, as delta-encoded integers. Mirrors backend `HandwrittenPage`; pinned by a
 * shared test vector.
 */

export const PAGE_WIDTH = 1000;
export const PAGE_HEIGHT = 1414;
export const MAX_PAGE_POINTS = 25_000;
export const MAX_PAGES = 4;

export type Nib = 'fountain' | 'brush' | 'pencil' | 'ballpoint';
export type InkName = 'ink' | 'blue-black' | 'sepia' | 'seal';

export interface Pen {
  nib: Nib;
  ink: InkName;
}

export const NIBS: { value: Nib; label: string }[] = [
  { value: 'fountain', label: 'Fountain pen' },
  { value: 'brush', label: 'Brush' },
  { value: 'pencil', label: 'Pencil' },
  { value: 'ballpoint', label: 'Ballpoint' },
];

export const INKS: { value: InkName; label: string }[] = [
  { value: 'ink', label: 'Ink' },
  { value: 'blue-black', label: 'Blue-black' },
  { value: 'sepia', label: 'Sepia' },
  { value: 'seal', label: 'Seal red' },
];

export interface Page {
  format: 'writeproof.page';
  version: 1;
  /** First pen-down; published only with replay (it is part of the timing). */
  capturedAt?: string;
  device: InputDevice;
  pen: Pen;
  /** Per stroke: x, y (tenths of a unit) and pressure (0–255) per point; first absolute, then deltas. */
  ink: number[][];
  /** Per stroke: ms since the page's first pen-down; first absolute, then deltas. Only with replay. */
  timing?: number[][];
  /** Set by the server on published pages: the hash of the timing, published or not. */
  timingHash?: string;
}

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

/** Encodes what was written on a page-sized pad (`PAGE_WIDTH` × `PAGE_HEIGHT` units). */
export function pageFromSample(sample: HandwritingSample, pen: Pen): Page {
  const ink: number[][] = [];
  const timing: number[][] = [];
  for (const stroke of sample.strokes) {
    const xs: number[] = [];
    const ts: number[] = [];
    let px = 0;
    let py = 0;
    let pp = 0;
    let pt = 0;
    stroke.forEach((point, i) => {
      const x = clamp(Math.round(point.x * 10), 0, PAGE_WIDTH * 10);
      const y = clamp(Math.round(point.y * 10), 0, PAGE_HEIGHT * 10);
      const p = clamp(Math.round(point.pressure * 255), 0, 255);
      const t = Math.max(Math.round(point.t), pt);
      xs.push(i === 0 ? x : x - px, i === 0 ? y : y - py, i === 0 ? p : p - pp);
      ts.push(i === 0 ? t : t - pt);
      px = x;
      py = y;
      pp = p;
      pt = t;
    });
    ink.push(xs);
    timing.push(ts);
  }
  return {
    format: 'writeproof.page',
    version: 1,
    capturedAt: sample.capturedAt,
    device: sample.device,
    pen,
    ink,
    timing,
  };
}

export function pagePointCount(page: Pick<Page, 'ink'>): number {
  return page.ink.reduce((n, stroke) => n + stroke.length / 3, 0);
}

/**
 * The page's strokes in pad units, for drawing. Without timing every point gets `t = 0`
 * (the page can be shown, not replayed).
 */
export function pageStrokes(page: Pick<Page, 'ink' | 'timing'>): Stroke[] {
  return page.ink.map((stroke, s) => {
    const ts = page.timing?.[s];
    const points: Stroke = [];
    let x = 0;
    let y = 0;
    let p = 0;
    let t = 0;
    for (let i = 0, k = 0; i < stroke.length; i += 3, k++) {
      x = i === 0 ? stroke[0] : x + stroke[i];
      y = i === 0 ? stroke[1] : y + stroke[i + 1];
      p = i === 0 ? stroke[2] : p + stroke[i + 2];
      t = ts ? (k === 0 ? ts[0] : t + ts[k]) : 0;
      points.push({ x: x / 10, y: y / 10, t, pressure: p / 255, penDown: i + 3 < stroke.length });
    }
    return points;
  });
}

const utf8 = (text: string) => new TextEncoder().encode(text);
const joinStrokes = (strokes: number[][]) => strokes.map((s) => s.join(',')).join(';');

export async function inkHash(page: Pick<Page, 'device' | 'pen' | 'ink'>): Promise<string> {
  const preimage = `writeproof/page-ink/v1\n${page.device}\n${page.pen.nib}\n${page.pen.ink}\n${joinStrokes(page.ink)}`;
  return toBase64Url(await sha256(utf8(preimage)));
}

/** Needs the timing: the author's own page, or a published one with replay. */
export async function timingHash(page: Pick<Page, 'capturedAt' | 'timing'>): Promise<string> {
  if (!page.capturedAt || !page.timing) {
    throw new Error('This page has no timing');
  }
  const preimage = `writeproof/page-timing/v1\n${page.capturedAt}\n${joinStrokes(page.timing)}`;
  return toBase64Url(await sha256(utf8(preimage)));
}

export async function pageHash(ink: string, timing: string): Promise<string> {
  return toBase64Url(await sha256(utf8(`writeproof/page/v1\n${ink}\n${timing}`)));
}

/** Commits to the pages in order and to whether readers may replay them. */
export async function pagesHash(replay: boolean, pageHashes: readonly string[]): Promise<string> {
  const preimage = [`writeproof/open-letter-pages/v1`, replay ? '1' : '0', ...pageHashes].join(
    '\n',
  );
  return toBase64Url(await sha256(utf8(preimage)));
}

/** The hash of a page the author just wrote (it has its timing). */
export async function ownPageHash(page: Page): Promise<string> {
  return pageHash(await inkHash(page), await timingHash(page));
}
