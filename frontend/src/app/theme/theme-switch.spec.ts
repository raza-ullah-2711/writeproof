import { TestBed } from '@angular/core/testing';
import { ThemeSwitch } from './theme-switch';

describe('ThemeSwitch', () => {
  beforeEach(() => {
    localStorage.clear();
    delete document.documentElement.dataset['theme'];
  });

  it('shows the remembered choice and applies a new one', async () => {
    localStorage.setItem('writeproof.theme', 'dark');
    const fixture = TestBed.createComponent(ThemeSwitch);
    fixture.detectChanges();
    await fixture.whenStable();
    const select: HTMLSelectElement = fixture.nativeElement.querySelector('select');

    expect(select.value).toBe('dark');

    select.value = 'light';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(document.documentElement.dataset['theme']).toBe('light');
    expect(localStorage.getItem('writeproof.theme')).toBe('light');
  });
});
