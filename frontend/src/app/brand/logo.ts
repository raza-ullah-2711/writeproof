import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * The signed wordmark: a handwritten W, "riteproof" in the serif italic, and a signature
 * underline ending in a seal-red dot. The ink follows the theme (`--ink`, `--seal`). Screen
 * readers get the plain name. Static files for other uses are in public/brand/.
 */
@Component({
  selector: 'app-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="sr-only">Writeproof</span>
    <span class="mark" aria-hidden="true">
      <svg class="w" viewBox="0 0 43 32">
        <path d="M2 4 C5 16 8 24 11 30 L20 10 L29 30 C33 22 37 12 41 2" /></svg
      ><span class="rest">riteproof</span>
      <svg class="flourish" viewBox="0 0 160 14">
        <path d="M4 7 C52 15 102 13 146 3" />
        <circle cx="151" cy="3.5" r="3.4" />
      </svg>
    </span>
  `,
  styles: `
    :host {
      display: inline-block;
      color: var(--ink);
      font-family: var(--font-display);
      line-height: 1;
    }
    .mark {
      display: inline-grid;
      grid-template-columns: auto auto;
      align-items: end;
    }
    .w {
      width: 1.6em;
      height: 1.2em;
      margin-right: -0.06em;
    }
    .w path,
    .flourish path {
      fill: none;
      stroke: currentColor;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    .w path {
      stroke-width: 3.6;
    }
    .rest {
      font-style: italic;
      font-weight: 400;
      font-variation-settings:
        'SOFT' 100,
        'WONK' 1;
      padding-bottom: 0.06em;
    }
    .flourish {
      grid-column: 1 / -1;
      /* Spans the words without widening them (an SVG is 300px wide by default). */
      width: 0;
      min-width: 100%;
      height: auto;
      margin-top: 0.1em;
    }
    .flourish path {
      stroke-width: 1.6;
    }
    .flourish circle {
      fill: var(--seal);
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
  `,
})
export class Logo {}
