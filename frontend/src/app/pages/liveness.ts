import { InputDevice, Stroke } from '../handwriting/handwriting-sample';

/**
 * The server's liveness heuristics (backend `LivenessChecker`, `FeatureExtractor`, `Stats`),
 * ported so a sealed letter's recipient can check its handwritten pages in their own browser:
 * the server never sees those pages. Pinned to the same fixtures and numbers as the Java
 * (`LivenessFixturesTest`), so both judge a page alike. Thresholds were calibrated on synthetic
 * strokes and must be revisited with real data, as on the server.
 */

export type LivenessFlag =
  'INSUFFICIENT_INPUT' | 'CONSTANT_VELOCITY' | 'NO_TIMING_VARIANCE' | 'CONSTANT_PRESSURE';

const STEP_MS = 10;
const MAX_FRAMES = 600;
const MIN_DURATION_MS = 300;
const MIN_POINTS = 20;
const MIN_SPEED_CV = 0.3;
const MIN_INTERVAL_CV = 0.005;
const MIN_INTERVALS = 30;
const MIN_PRESSURE_STD = 0.01;

export interface Liveness {
  flags: LivenessFlag[];
  durationMs: number;
  pointCount: number;
  /** Speed variability inside strokes, pooled over strokes. */
  speedCv: number;
}

/** Checks that strokes look written live by a hand, whoever wrote them. Needs real timing. */
export function checkLiveness(strokes: readonly Stroke[], device: InputDevice): Liveness {
  const pointCount = strokes.reduce((n, s) => n + s.length, 0);
  const first = strokes[0]?.[0];
  const last = strokes.at(-1)?.at(-1);
  const durationMs = first && last ? last.t - first.t : 0;
  const result = (flags: LivenessFlag[], speedCv: number): Liveness => ({
    flags,
    durationMs,
    pointCount,
    speedCv,
  });
  if (durationMs < MIN_DURATION_MS || pointCount < MIN_POINTS) {
    return result(['INSUFFICIENT_INPUT'], 0);
  }

  const step = Math.max(STEP_MS, durationMs / MAX_FRAMES);
  const pressures: number[] = [];
  let weighted = 0;
  let frames = 0;
  for (const stroke of strokes) {
    const { x, y, p } = resample(stroke, step);
    pressures.push(...p);
    const speeds = x.map((_, i) => {
      const a = Math.max(0, i - 1);
      const b = Math.min(x.length - 1, i + 1);
      const span = Math.max(1, b - a);
      return Math.hypot((x[b] - x[a]) / span, (y[b] - y[a]) / span) / step;
    });
    if (speeds.length >= 5) {
      const m = mean(speeds);
      if (m > 0) {
        weighted += (std(speeds) / m) * speeds.length;
        frames += speeds.length;
      }
    }
  }
  const speedCv = frames > 0 ? weighted / frames : 0;

  const flags: LivenessFlag[] = [];
  if (speedCv < MIN_SPEED_CV) {
    flags.push('CONSTANT_VELOCITY');
  }
  const intervals: number[] = [];
  for (const stroke of strokes) {
    for (let i = 1; i < stroke.length - 1; i++) {
      intervals.push(stroke[i].t - stroke[i - 1].t);
    }
  }
  if (intervals.length >= MIN_INTERVALS && cv(intervals) < MIN_INTERVAL_CV) {
    flags.push('NO_TIMING_VARIANCE');
  }
  if (device === 'pen' && std(pressures) < MIN_PRESSURE_STD) {
    flags.push('CONSTANT_PRESSURE');
  }
  return result(flags, speedCv);
}

/** Linear interpolation of x, y and pressure at fixed time steps across one stroke. */
function resample(stroke: Stroke, step: number): { x: number[]; y: number[]; p: number[] } {
  const t0 = stroke[0].t;
  const count = Math.floor((stroke[stroke.length - 1].t - t0) / step) + 1;
  const x: number[] = [];
  const y: number[] = [];
  const p: number[] = [];
  let j = 0;
  for (let i = 0; i < count; i++) {
    const t = t0 + i * step;
    while (j < stroke.length - 2 && stroke[j + 1].t < t) {
      j++;
    }
    const a = stroke[j];
    const b = stroke[Math.min(j + 1, stroke.length - 1)];
    const dt = b.t - a.t;
    const u = dt > 0 ? Math.min(1, Math.max(0, (t - a.t) / dt)) : 0;
    x.push(a.x + u * (b.x - a.x));
    y.push(a.y + u * (b.y - a.y));
    // The closing pen-up point reports 0 pressure; use the last in-contact pressure instead.
    const pb = b.penDown ? b.pressure : a.pressure;
    p.push(a.pressure + u * (pb - a.pressure));
  }
  return { x, y, p };
}

function mean(v: readonly number[]): number {
  return v.length > 0 ? v.reduce((s, n) => s + n, 0) / v.length : 0;
}

function std(v: readonly number[]): number {
  const m = mean(v);
  return v.length > 0 ? Math.sqrt(v.reduce((s, n) => s + (n - m) * (n - m), 0) / v.length) : 0;
}

function cv(v: readonly number[]): number {
  const m = mean(v);
  return m > 0 ? std(v) / m : 0;
}
