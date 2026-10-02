/**
 * One sampled position of the pen. Coordinates are CSS pixels relative to the pad's
 * top-left corner; `t` is milliseconds since the first pen-down of the sample.
 * The last point of every stroke has `penDown: false` (the pen-up event).
 */
export interface StrokePoint {
  x: number;
  y: number;
  t: number;
  pressure: number;
  penDown: boolean;
}

export type Stroke = StrokePoint[];

export type InputDevice = 'pen' | 'touch' | 'mouse';

/**
 * A complete handwriting capture: stroke dynamics, never an image.
 * `device` matters downstream: mice report a constant synthetic pressure (0.5),
 * so pressure is only a meaningful feature for pens and some touch screens.
 */
export interface HandwritingSample {
  format: 'writeproof.handwriting';
  version: 1;
  capturedAt: string;
  device: InputDevice;
  width: number;
  height: number;
  strokes: Stroke[];
}

export function sampleDuration(sample: Pick<HandwritingSample, 'strokes'>): number {
  const last = sample.strokes.at(-1)?.at(-1);
  return last ? last.t : 0;
}

export function pointCount(sample: Pick<HandwritingSample, 'strokes'>): number {
  return sample.strokes.reduce((n, stroke) => n + stroke.length, 0);
}

export function toJson(sample: HandwritingSample): string {
  return JSON.stringify(sample);
}

/** Parses and validates exported JSON; throws on anything that isn't a well-formed sample. */
export function parseSample(json: string): HandwritingSample {
  const value: unknown = JSON.parse(json);
  if (!isRecord(value) || value['format'] !== 'writeproof.handwriting' || value['version'] !== 1) {
    throw new Error('Not a Writeproof handwriting sample (v1)');
  }
  const { capturedAt, device, width, height, strokes } = value;
  if (typeof capturedAt !== 'string' || !['pen', 'touch', 'mouse'].includes(device as string)) {
    throw new Error('Invalid sample metadata');
  }
  if (!isPositive(width) || !isPositive(height) || !Array.isArray(strokes)) {
    throw new Error('Invalid sample dimensions or strokes');
  }
  let previousT = 0;
  for (const stroke of strokes) {
    if (!Array.isArray(stroke) || stroke.length === 0) {
      throw new Error('Strokes must be non-empty arrays');
    }
    stroke.forEach((point: unknown, i: number) => {
      if (!isPoint(point)) {
        throw new Error('Invalid stroke point');
      }
      if (point.t < previousT) {
        throw new Error('Timestamps must not decrease');
      }
      if (point.penDown !== i < stroke.length - 1) {
        throw new Error('Only the last point of a stroke may be pen-up');
      }
      previousT = point.t;
    });
  }
  return value as unknown as HandwritingSample;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isPositive(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

function isPoint(value: unknown): value is StrokePoint {
  return (
    isRecord(value) &&
    Number.isFinite(value['x']) &&
    Number.isFinite(value['y']) &&
    Number.isFinite(value['t']) &&
    typeof value['pressure'] === 'number' &&
    value['pressure'] >= 0 &&
    value['pressure'] <= 1 &&
    typeof value['penDown'] === 'boolean'
  );
}
