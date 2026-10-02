import { PointerSample, StrokeRecorder } from './stroke-recorder';

const at = (x: number, y: number, timeStamp: number, pressure = 0.5): PointerSample => ({
  x,
  y,
  timeStamp,
  pressure,
});

describe('StrokeRecorder', () => {
  let recorder: StrokeRecorder;

  beforeEach(() => (recorder = new StrokeRecorder()));

  it('records a stroke with times relative to the first pen-down and a final pen-up point', () => {
    recorder.down(1, 'pen', at(10, 20, 1000, 0.3));
    recorder.move(1, [at(12, 22, 1008, 0.4), at(15, 25, 1016, 0.6)]);
    recorder.up(1, at(16, 26, 1020, 0));

    expect(recorder.strokes).toEqual([
      [
        { x: 10, y: 20, t: 0, pressure: 0.3, penDown: true },
        { x: 12, y: 22, t: 8, pressure: 0.4, penDown: true },
        { x: 15, y: 25, t: 16, pressure: 0.6, penDown: true },
        { x: 16, y: 26, t: 20, pressure: 0, penDown: false },
      ],
    ]);
    expect(recorder.device).toBe('pen');
    expect(recorder.drawing).toBe(false);
  });

  it('keeps one timeline across strokes, so pen-up gaps are preserved', () => {
    recorder.down(1, 'mouse', at(0, 0, 500));
    recorder.up(1, at(1, 1, 600));
    recorder.down(1, 'mouse', at(5, 5, 900));
    recorder.up(1, at(6, 6, 950));

    expect(recorder.strokes.map((s) => s.map((p) => p.t))).toEqual([
      [0, 100],
      [400, 450],
    ]);
  });

  it('ignores a second pointer while one is drawing (palm or second finger)', () => {
    recorder.down(1, 'touch', at(0, 0, 0));
    expect(recorder.down(2, 'touch', at(50, 50, 5))).toBe(false);
    recorder.move(2, [at(60, 60, 10)]);
    recorder.up(2, at(70, 70, 15));
    recorder.up(1, at(1, 1, 20));

    expect(recorder.strokes).toHaveLength(1);
    expect(recorder.strokes[0].map((p) => p.x)).toEqual([0, 1]);
  });

  it('never lets time run backwards and clamps pressure to [0, 1]', () => {
    recorder.down(1, 'pen', at(0, 0, 100, 1.4));
    recorder.move(1, [at(1, 1, 120), at(2, 2, 115, -0.2)]);
    recorder.up(1, at(3, 3, 130));

    const [stroke] = recorder.strokes;
    expect(stroke.map((p) => p.t)).toEqual([0, 20, 20, 30]);
    expect(stroke[0].pressure).toBe(1);
    expect(stroke[2].pressure).toBe(0);
  });

  it('lifts the pen where it last was when the pointer is cancelled', () => {
    recorder.down(1, 'touch', at(10, 10, 0));
    recorder.move(1, [at(30, 40, 10)]);
    recorder.cancel(1, 25);

    expect(recorder.strokes[0].at(-1)).toEqual({
      x: 30,
      y: 40,
      t: 25,
      pressure: 0,
      penDown: false,
    });
    expect(recorder.drawing).toBe(false);
  });

  it('exposes the stroke in progress', () => {
    recorder.down(1, 'pen', at(0, 0, 0));
    expect(recorder.drawing).toBe(true);
    expect(recorder.strokes).toHaveLength(1);
  });

  it('clears everything, including the time origin', () => {
    recorder.down(1, 'pen', at(0, 0, 100));
    recorder.up(1, at(1, 1, 200));
    recorder.clear();
    recorder.down(1, 'mouse', at(0, 0, 5000));
    recorder.up(1, at(0, 0, 5010));

    expect(recorder.strokes).toHaveLength(1);
    expect(recorder.strokes[0][0].t).toBe(0);
    expect(recorder.device).toBe('mouse');
  });

  it('restores imported strokes but does not let them be extended', () => {
    const stroke = [
      { x: 0, y: 0, t: 0, pressure: 0.5, penDown: true },
      { x: 1, y: 1, t: 10, pressure: 0, penDown: false },
    ];
    recorder.restore([stroke], 'pen');

    expect(recorder.strokes).toEqual([stroke]);
    expect(recorder.down(1, 'pen', at(5, 5, 0))).toBe(false);
  });
});
