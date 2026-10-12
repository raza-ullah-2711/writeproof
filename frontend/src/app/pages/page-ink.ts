import { InputDevice, Stroke } from '../handwriting/handwriting-sample';
import { InkName, Nib, Pen } from './page-format';

/** Ink colours by name: theme tokens, so a page stays readable in both modes. */
export const INK_TOKENS: Record<InkName, string> = {
  ink: '--ink',
  'blue-black': '--page-ink-blue-black',
  sepia: '--page-ink-sepia',
  seal: '--seal',
};

/** When a token can't be read (e.g. in tests). */
const FALLBACK_INK: Record<InkName, string> = {
  ink: '#1b1f3a',
  'blue-black': '#1f2f5c',
  sepia: '#6b4423',
  seal: '#a8322d',
};

/** Reads the colour for an ink from the element's theme. */
export function inkColor(element: Element, ink: InkName): string {
  return getComputedStyle(element).getPropertyValue(INK_TOKENS[ink]).trim() || FALLBACK_INK[ink];
}

/** Base widths in page units (a page is 1000 units wide). */
const BASE: Record<Nib, number> = { fountain: 3.4, brush: 5.5, pencil: 2.2, ballpoint: 2.4 };
/** The broad edge of a fountain nib, held at 30°: thick on downstrokes, hairline across. */
const NIB_ANGLE = Math.PI / 6;

/**
 * Draws strokes the way the chosen pen would lay them down. Width follows pressure where the
 * device reports it; otherwise speed stands in for it (a slow hand leaves a fuller line). Speed
 * comes from the spacing of points, so it works without the timing too.
 */
export function drawPage(
  ctx: CanvasRenderingContext2D,
  strokes: readonly Stroke[],
  pen: Pen,
  device: InputDevice | null,
  color: string,
): void {
  const realPressure = device === 'pen';
  const base = BASE[pen.nib];
  ctx.save();
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  ctx.globalAlpha = pen.nib === 'pencil' ? 0.82 : 1;
  for (const stroke of strokes) {
    if (stroke.length === 1) {
      ctx.beginPath();
      ctx.arc(stroke[0].x, stroke[0].y, base / 2, 0, Math.PI * 2);
      ctx.fill();
      continue;
    }
    let previousWidth = -1;
    for (let i = 1; i < stroke.length; i++) {
      const a = stroke[i - 1];
      const b = stroke[i];
      const dx = b.x - a.x;
      const dy = b.y - a.y;
      const step = Math.hypot(dx, dy);
      const pressure = realPressure ? a.pressure : clamp(1.1 - step / 14, 0.25, 1);
      let width: number;
      switch (pen.nib) {
        case 'fountain': {
          const across = step === 0 ? 0.5 : Math.abs(Math.sin(Math.atan2(dy, dx) - NIB_ANGLE));
          width = base * (0.3 + 1.15 * across) * (0.7 + 0.6 * pressure);
          break;
        }
        case 'brush':
          width = base * (0.2 + 1.7 * pressure * pressure);
          break;
        case 'pencil':
          width = base * (0.75 + 0.5 * pressure);
          break;
        default:
          width = base;
      }
      // Ease width changes so a line swells instead of stepping.
      width = previousWidth < 0 ? width : previousWidth * 0.55 + width * 0.45;
      previousWidth = width;
      ctx.lineWidth = width;
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(b.x, b.y);
      ctx.stroke();
    }
  }
  ctx.restore();
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, v));
}
