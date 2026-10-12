import { TestBed } from '@angular/core/testing';
import { Page } from './page-format';
import { PageView } from './page-view';

const PAGE: Page = {
  format: 'writeproof.page',
  version: 1,
  device: 'pen',
  pen: { nib: 'brush', ink: 'seal' },
  ink: [[1000, 2000, 128, 50, -30, 2, 40, 10, -1]],
  timingHash: 'th',
};

describe('PageView', () => {
  async function render(page: Page, replay: boolean) {
    const fixture = TestBed.createComponent(PageView);
    fixture.componentRef.setInput('page', page);
    fixture.componentRef.setInput('label', 'Handwritten page 1 of 1, no transcript');
    fixture.componentRef.setInput('replay', replay);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the page as an image with its text alternative', async () => {
    const el = await render(PAGE, false);

    const canvas = el.querySelector('canvas')!;
    expect(canvas.getAttribute('role')).toBe('img');
    expect(canvas.getAttribute('aria-label')).toBe('Handwritten page 1 of 1, no transcript');
    expect(el.querySelector('button.replay')).toBeNull();
  });

  it('offers replay only when the author allowed it and the timing is there', async () => {
    expect((await render(PAGE, true)).querySelector('button.replay')).toBeNull();
    const timed = { ...PAGE, capturedAt: '2026-10-13T09:12:44.120Z', timing: [[0, 8, 9]] };
    expect((await render(timed, false)).querySelector('button.replay')).toBeNull();
    expect((await render(timed, true)).querySelector('button.replay')?.textContent).toContain(
      'Watch it being written',
    );
  });
});
