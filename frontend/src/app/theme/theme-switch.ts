import { Component, inject } from '@angular/core';
import { ThemeChoice, ThemeService } from './theme.service';

/** System, light or dark: a compact control for the page header. */
@Component({
  selector: 'app-theme-switch',
  template: `
    <label>
      <span class="sr-only">Theme</span>
      <select (change)="theme.set($any($event.target).value)" aria-label="Colour theme">
        @for (option of options; track option.value) {
          <!-- Selected per option: a [value] on the select would be set before its options exist. -->
          <option [value]="option.value" [selected]="option.value === theme.choice()">
            {{ option.label }}
          </option>
        }
      </select>
    </label>
  `,
  styles: `
    label {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      font-family: var(--font-ui);
      font-size: 0.8125rem;
      color: var(--text-secondary);
    }
    select {
      font: inherit;
      font-weight: 500;
      color: var(--text);
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 999px;
      padding: 0.35rem 0.75rem;
      box-shadow: var(--shadow-sm);
      cursor: pointer;
    }
  `,
})
export class ThemeSwitch {
  protected readonly theme = inject(ThemeService);
  protected readonly options: { value: ThemeChoice; label: string }[] = [
    { value: 'system', label: 'System' },
    { value: 'light', label: 'Light' },
    { value: 'dark', label: 'Dark' },
  ];
}
