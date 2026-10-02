import { Stroke } from './handwriting-sample';

/**
 * The strokes as they looked `elapsed` ms into the capture: every point with
 * `t <= elapsed`. Used to replay a sample at its original speed.
 */
export function strokesAt(strokes: readonly Stroke[], elapsed: number): Stroke[] {
  const visible: Stroke[] = [];
  for (const stroke of strokes) {
    if (stroke[0].t > elapsed) {
      break;
    }
    const end = stroke.findIndex((p) => p.t > elapsed);
    visible.push(end === -1 ? stroke : stroke.slice(0, end));
  }
  return visible;
}
