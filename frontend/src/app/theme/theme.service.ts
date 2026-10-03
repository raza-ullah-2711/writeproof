import { Injectable, computed, signal } from '@angular/core';

export type ThemeChoice = 'system' | 'light' | 'dark';

const STORAGE_KEY = 'writeproof.theme';

/**
 * Light, dark, or whatever the system prefers (theme.scss holds the colours). An explicit choice is
 * remembered in this browser and set as `data-theme` on <html>, where it beats the OS setting.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly media =
    typeof matchMedia === 'function' ? matchMedia('(prefers-color-scheme: dark)') : null;
  private readonly systemDark = signal(this.media?.matches ?? false);

  readonly choice = signal<ThemeChoice>(storedTheme());
  /** True while the dark colours are showing. */
  readonly dark = computed(
    () => this.choice() === 'dark' || (this.choice() === 'system' && this.systemDark()),
  );

  constructor() {
    this.media?.addEventListener('change', (e) => this.systemDark.set(e.matches));
  }

  set(choice: ThemeChoice): void {
    try {
      if (choice === 'system') {
        localStorage.removeItem(STORAGE_KEY);
      } else {
        localStorage.setItem(STORAGE_KEY, choice);
      }
    } catch {
      // Not remembered, but still applied for this visit.
    }
    applyTheme(choice);
    this.choice.set(choice);
  }
}

/** Applies the remembered choice; call before bootstrapping so the first paint is right. */
export function applyStoredTheme(): void {
  applyTheme(storedTheme());
}

function storedTheme(): ThemeChoice {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === 'light' || stored === 'dark' ? stored : 'system';
  } catch {
    return 'system';
  }
}

function applyTheme(choice: ThemeChoice): void {
  const root = document.documentElement;
  if (choice === 'system') {
    delete root.dataset['theme'];
  } else {
    root.dataset['theme'] = choice;
  }
}
