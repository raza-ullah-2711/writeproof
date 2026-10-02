import { InputDevice, Stroke } from './handwriting-sample';

const BASE_WIDTH = 2.5;

/** Draws strokes as ink; line width follows pressure where the device reports real pressure. */
export function drawStrokes(
  ctx: CanvasRenderingContext2D,
  strokes: readonly Stroke[],
  device: InputDevice | null,
  color: string,
): void {
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  const realPressure = device === 'pen';
  for (const stroke of strokes) {
    if (stroke.length === 1) {
      ctx.beginPath();
      ctx.arc(stroke[0].x, stroke[0].y, BASE_WIDTH / 2, 0, Math.PI * 2);
      ctx.fill();
      continue;
    }
    for (let i = 1; i < stroke.length; i++) {
      const a = stroke[i - 1];
      const b = stroke[i];
      ctx.lineWidth = realPressure ? BASE_WIDTH * (0.4 + 1.2 * a.pressure) : BASE_WIDTH;
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(b.x, b.y);
      ctx.stroke();
    }
  }
}
