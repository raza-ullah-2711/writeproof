import { TestBed } from '@angular/core/testing';
import { ThemeService, applyStoredTheme } from './theme.service';

describe('ThemeService', () => {
  beforeEach(() => {
    localStorage.clear();
    delete document.documentElement.dataset['theme'];
  });

  it('follows the system until a choice is made, then remembers and applies it', () => {
    const theme = TestBed.inject(ThemeService);
    expect(theme.choice()).toBe('system');
    expect(document.documentElement.dataset['theme']).toBeUndefined();

    theme.set('dark');
    expect(document.documentElement.dataset['theme']).toBe('dark');
    expect(theme.dark()).toBe(true);
    expect(localStorage.getItem('writeproof.theme')).toBe('dark');

    theme.set('system');
    expect(document.documentElement.dataset['theme']).toBeUndefined();
    expect(localStorage.getItem('writeproof.theme')).toBeNull();
  });

  it('applies the remembered choice before the app starts', () => {
    localStorage.setItem('writeproof.theme', 'light');
    applyStoredTheme();
    expect(document.documentElement.dataset['theme']).toBe('light');

    localStorage.setItem('writeproof.theme', 'purple');
    applyStoredTheme();
    expect(document.documentElement.dataset['theme']).toBeUndefined();
  });
});
