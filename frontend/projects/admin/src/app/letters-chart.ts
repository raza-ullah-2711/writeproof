import { Component, computed, input, signal } from '@angular/core';
import { Day } from './admin.service';

interface Bar {
  day: Day;
  x: number;
  sealedY: number;
  sealedH: number;
  openY: number;
  openH: number;
}

/**
 * Letters per day, sealed and open stacked: one bar per day, thin marks, 2px gap between
 * segments, recessive axis, hover tooltip, legend, and a table view for exact values.
 */
@Component({
  selector: 'app-letters-chart',
  template: `
    <figure class="viz-root">
      <figcaption>
        <span class="title">Letters per day, last 30 days</span>
        <span class="legend">
          <span class="key"><i class="swatch sealed"></i>Sealed</span>
          <span class="key"><i class="swatch open"></i>Open</span>
          <button type="button" class="link" (click)="table.set(!table())">
            {{ table() ? 'Show chart' : 'Show table' }}
          </button>
        </span>
      </figcaption>
      @if (table()) {
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Sealed</th>
              <th>Open</th>
            </tr>
          </thead>
          <tbody>
            @for (d of days(); track d.date) {
              <tr>
                <td>{{ d.date }}</td>
                <td>{{ d.sealed }}</td>
                <td>{{ d.open }}</td>
              </tr>
            }
          </tbody>
        </table>
      } @else {
        <div class="plot">
          <svg [attr.viewBox]="'0 0 ' + W + ' ' + H" role="img" [attr.aria-label]="summary()">
            @for (t of ticks(); track t.value) {
              <line class="grid" x1="28" [attr.x2]="W" [attr.y1]="t.y" [attr.y2]="t.y" />
              <text class="tick" x="22" [attr.y]="t.y + 3" text-anchor="end">{{ t.value }}</text>
            }
            @for (b of bars(); track b.day.date; let i = $index) {
              <g
                (mouseenter)="hover.set(i)"
                (mouseleave)="hover.set(null)"
                (focus)="hover.set(i)"
                (blur)="hover.set(null)"
                tabindex="0"
                [attr.aria-label]="label(b.day)"
              >
                <rect
                  class="hit"
                  [attr.x]="b.x - 1"
                  y="0"
                  [attr.width]="barW + 2"
                  [attr.height]="base"
                />
                @if (b.sealedH > 0) {
                  <rect
                    class="sealed"
                    [attr.x]="b.x"
                    [attr.y]="b.sealedY"
                    [attr.width]="barW"
                    [attr.height]="b.sealedH"
                    [attr.rx]="b.openH > 0 ? 0 : 2"
                  />
                }
                @if (b.openH > 0) {
                  <rect
                    class="open"
                    [attr.x]="b.x"
                    [attr.y]="b.openY"
                    [attr.width]="barW"
                    [attr.height]="b.openH"
                    rx="2"
                  />
                }
              </g>
            }
            <line class="axis" x1="28" [attr.x2]="W" [attr.y1]="base" [attr.y2]="base" />
            <text class="tick" x="30" [attr.y]="H - 2">{{ days()[0]?.date }}</text>
            <text class="tick" [attr.x]="W" [attr.y]="H - 2" text-anchor="end">
              {{ days().at(-1)?.date }}
            </text>
          </svg>
          @if (hovered(); as h) {
            <div class="tooltip" [style.left.%]="h.left">
              <strong>{{ h.day.date }}</strong>
              <span><i class="swatch sealed"></i>Sealed {{ h.day.sealed }}</span>
              <span><i class="swatch open"></i>Open {{ h.day.open }}</span>
            </div>
          }
        </div>
      }
    </figure>
  `,
  styles: `
    .viz-root {
      --surface-1: #fcfcfb;
      --text-primary: #0b0b0b;
      --text-secondary: #52514e;
      --grid: #e7e6e1;
      --series-1: #2a78d6;
      --series-2: #eb6834;
      margin: 0;
      padding: 0.75rem;
      background: var(--surface-1);
      border: 1px solid #e7e6e1;
      border-radius: 8px;
      color: var(--text-primary);
    }
    figcaption {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: 0.5rem;
      font-size: 0.875rem;
      margin-bottom: 0.5rem;
    }
    .title {
      font-weight: 600;
    }
    .legend {
      display: flex;
      gap: 0.75rem;
      align-items: center;
      color: var(--text-secondary);
    }
    .key {
      display: inline-flex;
      align-items: center;
      gap: 0.3rem;
    }
    .swatch {
      display: inline-block;
      width: 10px;
      height: 10px;
      border-radius: 2px;
    }
    .swatch.sealed,
    rect.sealed {
      background: var(--series-1);
      fill: var(--series-1);
    }
    .swatch.open,
    rect.open {
      background: var(--series-2);
      fill: var(--series-2);
    }
    .link {
      font: inherit;
      background: none;
      border: 0;
      padding: 0;
      color: #1d4f8f;
      text-decoration: underline;
      cursor: pointer;
    }
    .plot {
      position: relative;
    }
    svg {
      display: block;
      width: 100%;
      height: auto;
    }
    .grid {
      stroke: var(--grid);
      stroke-width: 1;
    }
    .axis {
      stroke: #b9b8b2;
      stroke-width: 1;
    }
    .tick {
      font-size: 9px;
      fill: var(--text-secondary);
    }
    .hit {
      fill: transparent;
    }
    g:focus {
      outline: none;
    }
    g:hover .hit,
    g:focus .hit {
      fill: rgba(0, 0, 0, 0.04);
    }
    .tooltip {
      position: absolute;
      top: 0;
      transform: translateX(-50%);
      display: flex;
      flex-direction: column;
      gap: 0.1rem;
      padding: 0.4rem 0.5rem;
      background: #fff;
      border: 1px solid #ddd;
      border-radius: 6px;
      box-shadow: 0 2px 6px rgba(0, 0, 0, 0.08);
      font-size: 0.75rem;
      pointer-events: none;
      white-space: nowrap;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.8125rem;
    }
    th,
    td {
      text-align: right;
      padding: 0.15rem 0.5rem;
      border-bottom: 1px solid #eee;
    }
    th:first-child,
    td:first-child {
      text-align: left;
    }
  `,
})
export class LettersChart {
  readonly days = input.required<Day[]>();

  protected readonly W = 600;
  protected readonly H = 160;
  protected readonly base = 140;
  private readonly top = 8;
  private readonly left = 32;
  protected readonly table = signal(false);
  protected readonly hover = signal<number | null>(null);

  protected readonly max = computed(() =>
    Math.max(1, ...this.days().map((d) => d.sealed + d.open)),
  );

  protected get barW(): number {
    const n = Math.max(1, this.days().length);
    return Math.max(2, (this.W - this.left) / n - 3);
  }

  protected readonly bars = computed<Bar[]>(() => {
    const n = Math.max(1, this.days().length);
    const step = (this.W - this.left) / n;
    const scale = (this.base - this.top) / this.max();
    return this.days().map((day, i) => {
      const sealedH = day.sealed * scale;
      const openH = day.open * scale;
      const sealedY = this.base - sealedH;
      // 2px surface gap between stacked segments.
      const openY = sealedY - openH - (sealedH > 0 && openH > 0 ? 2 : 0);
      return { day, x: this.left + i * step + 1.5, sealedY, sealedH, openY, openH };
    });
  });

  protected readonly ticks = computed(() => {
    const max = this.max();
    const step = niceStep(max);
    const ticks = [];
    for (let v = 0; v <= max; v += step) {
      ticks.push({ value: v, y: this.base - (v * (this.base - this.top)) / max });
    }
    return ticks;
  });

  protected readonly hovered = computed(() => {
    const i = this.hover();
    const bar = i === null ? undefined : this.bars()[i];
    return bar ? { day: bar.day, left: ((bar.x + this.barW / 2) / this.W) * 100 } : null;
  });

  protected readonly summary = computed(() => {
    const total = this.days().reduce((n, d) => n + d.sealed + d.open, 0);
    return `${total} letters in the last ${this.days().length} days. Use "Show table" for each day.`;
  });

  protected label(d: Day): string {
    return `${d.date}: ${d.sealed} sealed, ${d.open} open`;
  }
}

function niceStep(max: number): number {
  const raw = max / 4;
  const pow = 10 ** Math.floor(Math.log10(Math.max(raw, 1)));
  const n = raw / pow;
  return Math.max(1, (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * pow);
}
