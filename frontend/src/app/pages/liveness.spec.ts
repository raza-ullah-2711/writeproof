import live from '../../testing/liveness/live-page.json';
import scripted from '../../testing/liveness/scripted-page.json';
import { checkLiveness } from './liveness';
import { Page, pageStrokes } from './page-format';

/** Same fixtures and numbers as backend LivenessFixturesTest: browser and server must agree. */
describe('liveness checks (shared fixtures with the server)', () => {
  it('finds no flags on a page written live', () => {
    const page = live as Page;
    const result = checkLiveness(pageStrokes(page), page.device);

    expect(result.speedCv).toBeCloseTo(0.5684741784282148, 9);
    expect(result.durationMs).toBe(8585);
    expect(result.pointCount).toBe(1627);
    expect(result.flags).toEqual([]);
  });

  it('flags a scripted page for constant speed and pressure', () => {
    const page = scripted as Page;
    const result = checkLiveness(pageStrokes(page), page.device);

    expect(result.speedCv).toBeCloseTo(0.10822351085246053, 9);
    expect(result.flags).toEqual(['CONSTANT_VELOCITY', 'CONSTANT_PRESSURE']);
  });

  it('needs enough writing to judge', () => {
    const dot = [[{ x: 1, y: 1, t: 0, pressure: 0.5, penDown: false }]];
    expect(checkLiveness(dot, 'mouse').flags).toEqual(['INSUFFICIENT_INPUT']);
  });
});
