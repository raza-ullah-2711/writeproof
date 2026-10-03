import { TestBed } from '@angular/core/testing';
import { Logo } from './logo';

describe('Logo', () => {
  it('gives screen readers the plain name and hides the drawing', async () => {
    const fixture = TestBed.createComponent(Logo);
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.querySelector('.sr-only')?.textContent).toBe('Writeproof');
    expect(el.querySelector('.mark')?.getAttribute('aria-hidden')).toBe('true');
    expect(el.querySelector('.rest')?.textContent).toBe('riteproof');
  });
});
