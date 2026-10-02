import { TestBed } from '@angular/core/testing';
import { fakeContext, pointer } from '../../testing/pointer';
import { HandwritingPad } from './handwriting-pad';
import { HandwritingSample } from './handwriting-sample';

describe('HandwritingPad', () => {
  let ctx: CanvasRenderingContext2D;

  beforeEach(() => {
    ctx = fakeContext();
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(ctx as never);
    // Displayed at half the pad's logical size (300x120 for 600x240), offset on the page.
    vi.spyOn(HTMLCanvasElement.prototype, 'getBoundingClientRect').mockReturnValue(
      new DOMRect(100, 50, 300, 120),
    );
  });

  afterEach(() => vi.restoreAllMocks());

  async function create() {
    const fixture = TestBed.createComponent(HandwritingPad);
    await fixture.whenStable();
    const pad = fixture.componentInstance;
    const canvas = (fixture.nativeElement as HTMLElement).querySelector('canvas')!;
    const emitted: HandwritingSample[] = [];
    pad.sampleChange.subscribe((s) => emitted.push(s));
    return { pad, canvas, emitted };
  }

  it('records pointer input as stroke dynamics in pad coordinates', async () => {
    const { pad, canvas, emitted } = await create();

    canvas.dispatchEvent(
      pointer('pointerdown', { clientX: 110, clientY: 60, pressure: 0.25, timeStamp: 1000 }),
    );
    canvas.dispatchEvent(
      pointer('pointermove', {
        clientX: 130,
        clientY: 70,
        timeStamp: 1016,
        coalesced: [
          pointer('pointermove', { clientX: 120, clientY: 65, pressure: 0.5, timeStamp: 1008 }),
          pointer('pointermove', { clientX: 130, clientY: 70, pressure: 0.75, timeStamp: 1016 }),
        ],
      }),
    );
    canvas.dispatchEvent(
      pointer('pointerup', { clientX: 130, clientY: 70, pressure: 0, timeStamp: 1030 }),
    );

    expect(emitted).toHaveLength(1);
    const sample = emitted[0];
    expect(sample).toMatchObject({
      format: 'writeproof.handwriting',
      version: 1,
      device: 'pen',
      width: 600,
      height: 240,
    });
    expect(sample.strokes).toEqual([
      [
        { x: 20, y: 20, t: 0, pressure: 0.25, penDown: true },
        { x: 40, y: 30, t: 8, pressure: 0.5, penDown: true },
        { x: 60, y: 40, t: 16, pressure: 0.75, penDown: true },
        { x: 60, y: 40, t: 30, pressure: 0, penDown: false },
      ],
    ]);
    expect(pad.sample()).toEqual(sample);
    expect(ctx.lineTo).toHaveBeenCalled();
  });

  it('ignores moves without a pen-down and non-primary mouse buttons', async () => {
    const { pad, canvas } = await create();

    canvas.dispatchEvent(pointer('pointermove', { clientX: 120, clientY: 60, timeStamp: 5 }));
    canvas.dispatchEvent(
      pointer('pointerdown', { pointerType: 'mouse', button: 2, clientX: 120, timeStamp: 6 }),
    );
    canvas.dispatchEvent(
      pointer('pointerup', { pointerType: 'mouse', clientX: 120, timeStamp: 7 }),
    );

    expect(pad.empty()).toBe(true);
    expect(pad.sample()).toBeNull();
  });

  it('ends the stroke on pointercancel', async () => {
    const { canvas, emitted } = await create();

    canvas.dispatchEvent(pointer('pointerdown', { clientX: 110, clientY: 60, timeStamp: 0 }));
    canvas.dispatchEvent(pointer('pointercancel', { timeStamp: 40 }));

    expect(emitted[0].strokes[0].at(-1)).toEqual({
      x: 20,
      y: 20,
      t: 40,
      pressure: 0,
      penDown: false,
    });
  });

  it('clears the recording', async () => {
    const { pad, canvas } = await create();
    canvas.dispatchEvent(pointer('pointerdown', { clientX: 110, clientY: 60, timeStamp: 0 }));
    canvas.dispatchEvent(pointer('pointerup', { clientX: 110, clientY: 60, timeStamp: 10 }));

    pad.clear();

    expect(pad.empty()).toBe(true);
  });

  it('replays the sample over its original duration', async () => {
    vi.useFakeTimers({ toFake: ['requestAnimationFrame', 'cancelAnimationFrame', 'performance'] });
    try {
      const { pad, canvas } = await create();
      canvas.dispatchEvent(pointer('pointerdown', { clientX: 110, clientY: 60, timeStamp: 0 }));
      canvas.dispatchEvent(pointer('pointermove', { clientX: 150, clientY: 60, timeStamp: 200 }));
      canvas.dispatchEvent(pointer('pointerup', { clientX: 150, clientY: 60, timeStamp: 400 }));

      pad.replay();
      expect(pad.replaying()).toBe(true);
      vi.advanceTimersByTime(200);
      expect(pad.replaying()).toBe(true);
      vi.advanceTimersByTime(300);
      expect(pad.replaying()).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('loads an exported sample for replay', async () => {
    const { pad } = await create();
    const sample: HandwritingSample = {
      format: 'writeproof.handwriting',
      version: 1,
      capturedAt: '2026-10-02T12:00:00.000Z',
      device: 'touch',
      width: 600,
      height: 240,
      strokes: [
        [
          { x: 1, y: 1, t: 0, pressure: 0.5, penDown: true },
          { x: 2, y: 2, t: 10, pressure: 0, penDown: false },
        ],
      ],
    };

    pad.load(sample);

    expect(pad.sample()).toEqual(sample);
  });
});
