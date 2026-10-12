import { DecimalPipe } from '@angular/common';
import { Component, computed, input, signal, viewChildren } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HandwritingPad } from '../handwriting/handwriting-pad';
import { HandwritingSample, pointCount } from '../handwriting/handwriting-sample';
import {
  INKS,
  InkName,
  MAX_PAGES,
  MAX_PAGE_POINTS,
  NIBS,
  Nib,
  PAGE_HEIGHT,
  PAGE_WIDTH,
  Page,
  Pen,
  pageFromSample,
} from './page-format';

/**
 * Writing a letter by hand: a pen and ink, up to four pages with tabs, undo, and how much room is
 * left on the page. Shared by open and sealed letters (docs/handwritten-pages.md).
 */
@Component({
  selector: 'app-page-composer',
  imports: [DecimalPipe, FormsModule, HandwritingPad],
  template: `
    <div class="pen">
      <label>
        Pen
        <select name="nib" [ngModel]="nib()" (ngModelChange)="nib.set($event)">
          @for (n of nibs; track n.value) {
            <option [value]="n.value">{{ n.label }}</option>
          }
        </select>
      </label>
      <label>
        Ink
        <select name="ink" [ngModel]="ink()" (ngModelChange)="ink.set($event)">
          @for (i of inks; track i.value) {
            <option [value]="i.value">{{ i.label }}</option>
          }
        </select>
      </label>
    </div>

    <div class="page-tabs" role="tablist" aria-label="Pages">
      @for (id of pageIds(); track id; let i = $index) {
        <button
          type="button"
          role="tab"
          [attr.aria-selected]="current() === i"
          [class.on]="current() === i"
          (click)="current.set(i)"
        >
          Page {{ i + 1 }}
        </button>
      }
      @if (pageIds().length < maxPages) {
        <button type="button" class="add" (click)="addPage()">+ Add page</button>
      }
    </div>

    @for (id of pageIds(); track id; let i = $index) {
      <app-handwriting-pad
        #pagePad
        class="page-pad"
        [hidden]="current() !== i"
        [width]="pageWidth"
        [height]="pageHeight"
        [ruled]="true"
        [pen]="pen()"
        (sampleChange)="countPoints(id, $event)"
      />
    }

    <div class="page-actions">
      <button
        type="button"
        (click)="currentPad()?.undo()"
        [disabled]="disabled() || currentPad()?.empty()"
      >
        Undo
      </button>
      <button type="button" (click)="clearPage()" [disabled]="disabled() || currentPad()?.empty()">
        Clear page
      </button>
      @if (pageIds().length > 1) {
        <button type="button" (click)="removePage()" [disabled]="disabled()">Remove page</button>
      }
      <span class="hint" [class.warn]="currentPoints() > maxPoints * 0.9">
        {{ currentPoints() | number }} / {{ maxPoints | number }} points
      </span>
    </div>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
      font-size: 0.875rem;
    }
    .pen {
      display: flex;
      flex-wrap: wrap;
      gap: 1rem;
    }
    label {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
    }
    .page-pad {
      max-width: 34rem;
    }
    .page-tabs,
    .page-actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.4rem;
    }
    .page-tabs button {
      padding: 0.35rem 0.85rem;
    }
    .page-tabs button.on {
      color: var(--primary-text);
      background: var(--primary-bg);
      border-color: transparent;
    }
    .page-tabs button.add {
      border-style: dashed;
    }
    .hint {
      font-size: 0.8125rem;
      color: var(--text-secondary);
    }
    .hint.warn {
      color: var(--warning-text);
    }
  `,
})
export class PageComposer {
  readonly disabled = input(false);

  private readonly pads = viewChildren('pagePad', { read: HandwritingPad });
  protected readonly nibs = NIBS;
  protected readonly inks = INKS;
  protected readonly nib = signal<Nib>('fountain');
  protected readonly ink = signal<InkName>('ink');
  readonly pen = computed<Pen>(() => ({ nib: this.nib(), ink: this.ink() }));
  private nextPageId = 1;
  protected readonly pageIds = signal<number[]>([0]);
  protected readonly current = signal(0);
  protected readonly currentPad = computed(() => this.pads()[this.current()]);
  private readonly points = signal<Record<number, number>>({});
  protected readonly currentPoints = computed(
    () => this.points()[this.pageIds()[this.current()]] ?? 0,
  );
  /** Something is written on at least one page. */
  readonly hasWriting = computed(() => this.pads().some((p) => !p.empty()));
  protected readonly maxPages = MAX_PAGES;
  protected readonly maxPoints = MAX_PAGE_POINTS;
  protected readonly pageWidth = PAGE_WIDTH;
  protected readonly pageHeight = PAGE_HEIGHT;

  /** The written pages, in order, with their timing (blank pages are left out). */
  pages(): Page[] {
    return this.pads()
      .map((p) => p.sample())
      .filter((s): s is HandwritingSample => s !== null)
      .map((s) => pageFromSample(s, this.pen()));
  }

  /** Starts over with one blank page. */
  reset(): void {
    this.pads().forEach((p) => p.clear());
    this.pageIds.set([this.nextPageId++]);
    this.current.set(0);
    this.points.set({});
  }

  protected addPage(): void {
    if (this.pageIds().length >= MAX_PAGES) {
      return;
    }
    this.pageIds.update((ids) => [...ids, this.nextPageId++]);
    this.current.set(this.pageIds().length - 1);
  }

  protected removePage(): void {
    const index = this.current();
    const id = this.pageIds()[index];
    this.pageIds.update((ids) => ids.filter((i) => i !== id));
    this.points.update(({ [id]: _, ...rest }) => rest);
    this.current.set(Math.max(0, index - 1));
  }

  protected clearPage(): void {
    this.currentPad()?.clear();
    this.points.update((all) => ({ ...all, [this.pageIds()[this.current()]]: 0 }));
  }

  protected countPoints(id: number, sample: HandwritingSample): void {
    this.points.update((all) => ({ ...all, [id]: pointCount(sample) }));
  }
}
