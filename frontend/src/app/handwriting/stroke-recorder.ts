import { InputDevice, Stroke, StrokePoint } from './handwriting-sample';

/** A raw pointer reading, already translated into pad coordinates. */
export interface PointerSample {
  x: number;
  y: number;
  /** High-resolution event time in ms (e.g. `PointerEvent.timeStamp`). */
  timeStamp: number;
  pressure: number;
}

/**
 * Turns pointer down/move/up readings into strokes. DOM-free so it can be tested
 * directly. Only one pointer is tracked at a time, so a resting palm or a second
 * finger can't inject points into the stroke in progress.
 */
export class StrokeRecorder {
  private readonly finished: Stroke[] = [];
  private current: Stroke | null = null;
  private activePointer: number | null = null;
  private origin: number | null = null;
  private lastT = 0;
  private _device: InputDevice | null = null;

  get strokes(): readonly Stroke[] {
    return this.current ? [...this.finished, this.current] : this.finished;
  }

  get device(): InputDevice | null {
    return this._device;
  }

  get drawing(): boolean {
    return this.current !== null;
  }

  /** Returns false (and records nothing) if another pointer is already drawing. */
  down(pointerId: number, device: InputDevice, sample: PointerSample): boolean {
    if (this.activePointer !== null || Number.isNaN(this.origin)) {
      return false;
    }
    this.activePointer = pointerId;
    this._device ??= device;
    this.origin ??= sample.timeStamp;
    this.current = [this.point(sample, true)];
    return true;
  }

  move(pointerId: number, samples: readonly PointerSample[]): void {
    if (pointerId !== this.activePointer || !this.current) {
      return;
    }
    for (const sample of samples) {
      this.current.push(this.point(sample, true));
    }
  }

  /** Ends the stroke with a pen-up point. Also used for `pointercancel`. */
  up(pointerId: number, sample: PointerSample): void {
    if (pointerId !== this.activePointer || !this.current) {
      return;
    }
    this.current.push(this.point(sample, false));
    this.finished.push(this.current);
    this.current = null;
    this.activePointer = null;
  }

  /**
   * Ends the stroke when the browser cancels the pointer (e.g. a scroll or palm takeover).
   * Cancel events carry no reliable position, so the pen lifts where it last was.
   */
  cancel(pointerId: number, timeStamp: number): void {
    const last = this.current?.at(-1);
    if (last) {
      this.up(pointerId, { x: last.x, y: last.y, pressure: 0, timeStamp });
    }
  }

  /** Replaces the recording with previously captured strokes (e.g. an imported sample). */
  restore(strokes: readonly Stroke[], device: InputDevice): void {
    this.clear();
    this.finished.push(...strokes.map((stroke) => stroke.map((p) => ({ ...p }))));
    this._device = device;
    this.lastT = strokes.at(-1)?.at(-1)?.t ?? 0;
    this.origin = Number.NaN; // restored samples can't be extended
  }

  clear(): void {
    this.finished.length = 0;
    this.current = null;
    this.activePointer = null;
    this.origin = null;
    this.lastT = 0;
    this._device = null;
  }

  private point(sample: PointerSample, penDown: boolean): StrokePoint {
    // Timestamps are relative to the first pen-down and never run backwards,
    // even if a coalesced event arrives with a slightly earlier time.
    const t = Math.max(this.lastT, round(sample.timeStamp - (this.origin ?? sample.timeStamp), 1));
    this.lastT = t;
    return {
      x: round(sample.x, 2),
      y: round(sample.y, 2),
      t,
      pressure: round(clamp(sample.pressure, 0, 1), 3),
      penDown,
    };
  }
}

function round(value: number, decimals: number): number {
  const f = 10 ** decimals;
  return Math.round(value * f) / f;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
