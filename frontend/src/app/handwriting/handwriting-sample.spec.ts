import {
  HandwritingSample,
  parseSample,
  pointCount,
  sampleDuration,
  toJson,
} from './handwriting-sample';

function sample(overrides: Partial<HandwritingSample> = {}): HandwritingSample {
  return {
    format: 'writeproof.handwriting',
    version: 1,
    capturedAt: '2026-10-02T12:00:00.000Z',
    device: 'pen',
    width: 600,
    height: 240,
    strokes: [
      [
        { x: 1, y: 2, t: 0, pressure: 0.4, penDown: true },
        { x: 3, y: 4, t: 12.5, pressure: 0.6, penDown: true },
        { x: 3, y: 5, t: 20, pressure: 0, penDown: false },
      ],
      [
        { x: 9, y: 9, t: 300, pressure: 0.5, penDown: true },
        { x: 9, y: 9, t: 310, pressure: 0, penDown: false },
      ],
    ],
    ...overrides,
  };
}

describe('handwriting sample', () => {
  it('round-trips through JSON', () => {
    expect(parseSample(toJson(sample()))).toEqual(sample());
  });

  it('reports duration and point count', () => {
    expect(sampleDuration(sample())).toBe(310);
    expect(pointCount(sample())).toBe(5);
    expect(sampleDuration(sample({ strokes: [] }))).toBe(0);
  });

  it('rejects files that are not samples', () => {
    expect(() => parseSample('{"hello":"world"}')).toThrow(/Not a Writeproof/);
    expect(() => parseSample(toJson({ ...sample(), version: 2 as 1 }))).toThrow();
    expect(() => parseSample('not json')).toThrow();
  });

  it('rejects invalid points', () => {
    const bad = sample();
    bad.strokes[0][1] = { ...bad.strokes[0][1], pressure: 2 };
    expect(() => parseSample(toJson(bad))).toThrow(/point/);
  });

  it('rejects timestamps that go backwards', () => {
    const bad = sample();
    bad.strokes[1][0] = { ...bad.strokes[1][0], t: 5 };
    expect(() => parseSample(toJson(bad))).toThrow(/Timestamps/);
  });

  it('requires exactly the last point of each stroke to be pen-up', () => {
    const missingUp = sample();
    missingUp.strokes[0][2] = { ...missingUp.strokes[0][2], penDown: true };
    const earlyUp = sample();
    earlyUp.strokes[0][0] = { ...earlyUp.strokes[0][0], penDown: false };

    expect(() => parseSample(toJson(missingUp))).toThrow(/pen-up/);
    expect(() => parseSample(toJson(earlyUp))).toThrow(/pen-up/);
  });
});
