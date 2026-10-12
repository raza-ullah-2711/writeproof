import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { strokesAt } from '../handwriting/replay';
import { ThemeService } from '../theme/theme.service';
import { PAGE_HEIGHT, PAGE_WIDTH, Page, pageStrokes } from './page-format';
import { drawPage, inkColor } from './page-ink';

/** Canvas pixels per page unit: crisp on most screens without a huge canvas per page. */
const RESOLUTION = 1.5;

/**
 * Shows a handwritten page as its pen laid it down. When the author allowed replay (the page
 * carries its timing), readers can watch it being written at its real speed.
 */
@Component({
  selector: 'app-page-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="sheet">
      <canvas
        #canvas
        [width]="width"
        [height]="height"
        role="img"
        [attr.aria-label]="label()"
      ></canvas>
      @if (canReplay()) {
        <figcaption>
          <button type="button" class="replay" (click)="toggleReplay()">
            {{ replaying() ? 'Stop' : '▶ Watch it being written' }}
          </button>
        </figcaption>
      }
    </figure>
  `,
  styles: `
    :host {
      display: block;
    }
    .sheet {
      margin: 0;
    }
    canvas {
      display: block;
      width: 100%;
      height: auto;
      aspect-ratio: 1000 / 1414;
      background-color: var(--surface);
      background-image: linear-gradient(
        to bottom,
        transparent calc(100% - 1px),
        var(--rule) calc(100% - 1px)
      );
      background-size: 100% calc(100% / 14);
      border: 1px solid var(--paper-border);
      border-radius: var(--radius);
      box-shadow: var(--shadow-md);
    }
    figcaption {
      margin-top: 0.6rem;
    }
  `,
})
export class PageView implements OnDestroy {
  readonly page = input.required<Page>();
  /** The canvas's text alternative: the transcript, or a description of the page. */
  readonly label = input.required<string>();
  /** The author allowed replay (the page then carries its timing). */
  readonly replay = input(false);

  protected readonly width = Math.round(PAGE_WIDTH * RESOLUTION);
  protected readonly height = Math.round(PAGE_HEIGHT * RESOLUTION);
  protected readonly replaying = signal(false);
  private readonly reducedMotion =
    typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
  protected readonly canReplay = computed(
    () => this.replay() && !!this.page().timing && !this.reducedMotion,
  );

  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private readonly theme = inject(ThemeService);
  private readonly strokes = computed(() => pageStrokes(this.page()));
  private ctx: CanvasRenderingContext2D | null = null;
  private frame: number | null = null;

  constructor() {
    afterNextRender(() => {
      this.ctx = this.canvas().nativeElement.getContext('2d');
      this.draw(this.strokes());
    });
    effect(() => {
      this.theme.dark();
      this.draw(this.strokes());
    });
  }

  ngOnDestroy(): void {
    this.stop();
  }

  protected toggleReplay(): void {
    if (this.replaying()) {
      this.stop();
      this.draw(this.strokes());
      return;
    }
    const strokes = this.strokes();
    const duration = strokes.at(-1)?.at(-1)?.t ?? 0;
    const start = performance.now();
    this.replaying.set(true);
    const tick = (now: number) => {
      const elapsed = now - start;
      this.draw(strokesAt(strokes, elapsed));
      if (elapsed >= duration) {
        this.stop();
        this.draw(strokes);
        return;
      }
      this.frame = requestAnimationFrame(tick);
    };
    this.frame = requestAnimationFrame(tick);
  }

  private stop(): void {
    if (this.frame !== null) {
      cancelAnimationFrame(this.frame);
      this.frame = null;
    }
    this.replaying.set(false);
  }

  private draw(strokes: Parameters<typeof drawPage>[1]): void {
    const ctx = this.ctx;
    if (!ctx) {
      return;
    }
    const page = this.page();
    ctx.setTransform(RESOLUTION, 0, 0, RESOLUTION, 0, 0);
    ctx.clearRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT);
    drawPage(
      ctx,
      strokes,
      page.pen,
      page.device,
      inkColor(this.canvas().nativeElement, page.pen.ink),
    );
  }
}
