import {
  Component,
  ElementRef,
  OnDestroy,
  afterNextRender,
  computed,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { HandwritingSample, InputDevice, Stroke } from './handwriting-sample';
import { drawStrokes } from './ink';
import { strokesAt } from './replay';
import { PointerSample, StrokeRecorder } from './stroke-recorder';

const INK = '#1b1f3a';

/**
 * A canvas that records handwriting as stroke dynamics via Pointer Events:
 * `{x, y, t, pressure, penDown}` per sample, using coalesced events so fast
 * strokes keep their full sampling rate. Coordinates are in pad units
 * (`width` x `height`) regardless of how large the canvas is displayed.
 */
@Component({
  selector: 'app-handwriting-pad',
  templateUrl: './handwriting-pad.html',
  styleUrl: './handwriting-pad.scss',
})
export class HandwritingPad implements OnDestroy {
  readonly width = input(600);
  readonly height = input(240);
  /** Emits the full sample each time a stroke is finished. */
  readonly sampleChange = output<HandwritingSample>();

  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private readonly recorder = new StrokeRecorder();
  private ctx: CanvasRenderingContext2D | null = null;
  private replayFrame: number | null = null;
  private capturedAt: string | null = null;

  private readonly _strokes = signal<readonly Stroke[]>([]);
  private readonly _device = signal<InputDevice | null>(null);

  readonly replaying = signal(false);
  readonly empty = computed(() => this._strokes().length === 0);
  readonly sample = computed<HandwritingSample | null>(() => {
    const device = this._device();
    if (this.empty() || !device) {
      return null;
    }
    return {
      format: 'writeproof.handwriting',
      version: 1,
      capturedAt: this.capturedAt ?? new Date().toISOString(),
      device,
      width: this.width(),
      height: this.height(),
      strokes: this._strokes().map((stroke) => [...stroke]),
    };
  });

  constructor() {
    afterNextRender(() => this.setUpCanvas());
  }

  ngOnDestroy(): void {
    this.stopReplay();
  }

  clear(): void {
    this.stopReplay();
    this.recorder.clear();
    this.capturedAt = null;
    this.publish();
    this.redraw(this.recorder.strokes);
  }

  /** Loads a previously exported sample so it can be viewed and replayed. */
  load(sample: HandwritingSample): void {
    this.stopReplay();
    this.recorder.restore(sample.strokes, sample.device);
    this.capturedAt = sample.capturedAt;
    this.publish();
    this.redraw(this.recorder.strokes);
  }

  /** Re-draws the sample at the speed it was written. */
  replay(): void {
    const strokes = this.recorder.strokes;
    if (strokes.length === 0) {
      return;
    }
    this.stopReplay();
    this.replaying.set(true);
    const duration = strokes.at(-1)!.at(-1)!.t;
    const start = performance.now();
    const frame = (now: number) => {
      const elapsed = now - start;
      this.redraw(strokesAt(strokes, elapsed));
      if (elapsed >= duration) {
        this.stopReplay();
        this.redraw(strokes);
        return;
      }
      this.replayFrame = requestAnimationFrame(frame);
    };
    this.replayFrame = requestAnimationFrame(frame);
  }

  protected onPointerDown(event: PointerEvent): void {
    if (event.pointerType === 'mouse' && event.button !== 0) {
      return;
    }
    this.stopReplay();
    if (!this.recorder.down(event.pointerId, deviceOf(event), this.toPad(event))) {
      return;
    }
    this.capturedAt ??= new Date().toISOString();
    this.canvas().nativeElement.setPointerCapture?.(event.pointerId);
    event.preventDefault();
    this.redraw(this.recorder.strokes);
  }

  protected onPointerMove(event: PointerEvent): void {
    if (!this.recorder.drawing) {
      return;
    }
    const coalesced = event.getCoalescedEvents?.() ?? [];
    const events = coalesced.length > 0 ? coalesced : [event];
    this.recorder.move(
      event.pointerId,
      events.map((e) => this.toPad(e)),
    );
    this.redraw(this.recorder.strokes);
  }

  protected onPointerUp(event: PointerEvent): void {
    this.finishStroke(() => this.recorder.up(event.pointerId, this.toPad(event)));
  }

  protected onPointerCancel(event: PointerEvent): void {
    this.finishStroke(() => this.recorder.cancel(event.pointerId, event.timeStamp));
  }

  private finishStroke(end: () => void): void {
    if (!this.recorder.drawing) {
      return;
    }
    end();
    if (this.recorder.drawing) {
      return; // the event belonged to a pointer we're not tracking
    }
    this.publish();
    this.redraw(this.recorder.strokes);
    const sample = this.sample();
    if (sample) {
      this.sampleChange.emit(sample);
    }
  }

  private publish(): void {
    this._strokes.set(this.recorder.strokes.map((stroke) => [...stroke]));
    this._device.set(this.recorder.device);
  }

  private toPad(event: PointerEvent): PointerSample {
    const canvas = this.canvas().nativeElement;
    const rect = canvas.getBoundingClientRect();
    // Measure from the drawing surface, inside the border.
    const surfaceWidth = canvas.clientWidth || rect.width;
    const surfaceHeight = canvas.clientHeight || rect.height;
    const scaleX = surfaceWidth > 0 ? this.width() / surfaceWidth : 1;
    const scaleY = surfaceHeight > 0 ? this.height() / surfaceHeight : 1;
    return {
      x: (event.clientX - rect.left - canvas.clientLeft) * scaleX,
      y: (event.clientY - rect.top - canvas.clientTop) * scaleY,
      timeStamp: event.timeStamp,
      // Devices without pressure sensing report 0.5 while in contact.
      pressure: event.pressure,
    };
  }

  private setUpCanvas(): void {
    const canvas = this.canvas().nativeElement;
    const ratio = window.devicePixelRatio || 1;
    canvas.width = Math.round(this.width() * ratio);
    canvas.height = Math.round(this.height() * ratio);
    this.ctx = canvas.getContext('2d');
    this.ctx?.scale(ratio, ratio);
    this.redraw(this.recorder.strokes);
  }

  private redraw(strokes: readonly Stroke[]): void {
    if (!this.ctx) {
      return;
    }
    this.ctx.clearRect(0, 0, this.width(), this.height());
    drawStrokes(this.ctx, strokes, this.recorder.device, INK);
  }

  private stopReplay(): void {
    if (this.replayFrame !== null) {
      cancelAnimationFrame(this.replayFrame);
      this.replayFrame = null;
    }
    this.replaying.set(false);
  }
}

function deviceOf(event: PointerEvent): InputDevice {
  return event.pointerType === 'pen' || event.pointerType === 'touch' ? event.pointerType : 'mouse';
}
