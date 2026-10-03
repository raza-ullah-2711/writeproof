import { Component, inject } from '@angular/core';
import { ThemeChoice, ThemeService } from './theme.service';

/** System, light or dark: a compact control for the page header. */
@Component({
  selector: 'app-theme-switch',
  template: `
    <label>
      Theme
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
      font-family: system-ui, sans-serif;
      font-size: 0.8125rem;
      color: var(--text-secondary);
    }
    select {
      font: inherit;
      color: var(--text);
      background: var(--surface);
      border: 1px solid var(--border-strong);
      border-radius: 4px;
      padding: 0.1rem 0.25rem;
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
