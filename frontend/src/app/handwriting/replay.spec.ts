import { Stroke } from './handwriting-sample';
import { strokesAt } from './replay';

const p = (t: number, penDown = true) => ({ x: t, y: t, t, pressure: 0.5, penDown });

describe('strokesAt', () => {
  const strokes: Stroke[] = [
    [p(0), p(10), p(20, false)],
    [p(100), p(110, false)],
  ];

  it('shows nothing before t=0 and only the first point at t=0', () => {
    expect(strokesAt(strokes, -1)).toEqual([]);
    expect(strokesAt(strokes, 0)).toEqual([[p(0)]]);
  });

  it('shows a partial stroke mid-way through', () => {
    expect(strokesAt(strokes, 15)).toEqual([[p(0), p(10)]]);
  });

  it('shows nothing of a stroke that has not started during a pen-up gap', () => {
    expect(strokesAt(strokes, 50)).toEqual([strokes[0]]);
  });

  it('shows everything at or after the end', () => {
    expect(strokesAt(strokes, 110)).toEqual(strokes);
    expect(strokesAt(strokes, 1e9)).toEqual(strokes);
  });
});
