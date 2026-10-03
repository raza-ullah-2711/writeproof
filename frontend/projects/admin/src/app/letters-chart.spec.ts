import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Day } from './admin.service';
import { LettersChart } from './letters-chart';

@Component({
  imports: [LettersChart],
  template: `<app-letters-chart [days]="days" />`,
})
class Host {
  days: Day[] = [
    { date: '2026-10-01', sealed: 4, open: 0 },
    { date: '2026-10-02', sealed: 2, open: 2 },
    { date: '2026-10-03', sealed: 0, open: 0 },
  ];
}

describe('LettersChart', () => {
  async function render() {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('stacks sealed and open per day with a gap, scaled to the busiest day', async () => {
    const { el } = await render();
    const groups = el.querySelectorAll('svg g');

    expect(groups).toHaveLength(3);
    const tallest = groups[0].querySelector<SVGRectElement>('rect.sealed')!;
    expect(Number(tallest.getAttribute('height'))).toBeCloseTo(132);
    const sealed = groups[1].querySelector<SVGRectElement>('rect.sealed')!;
    const open = groups[1].querySelector<SVGRectElement>('rect.open')!;
    expect(Number(sealed.getAttribute('height'))).toBeCloseTo(66);
    expect(Number(open.getAttribute('y')) + Number(open.getAttribute('height'))).toBeCloseTo(
      Number(sealed.getAttribute('y')) - 2,
    );
    expect(groups[2].querySelectorAll('rect.sealed, rect.open')).toHaveLength(0);
    expect(el.querySelector('svg')?.getAttribute('aria-label')).toContain(
      '8 letters in the last 3 days',
    );
    expect(el.querySelectorAll('.legend .key')).toHaveLength(2);
  });

  it('shows a tooltip on hover and a table on request', async () => {
    const { fixture, el } = await render();

    el.querySelectorAll('svg g')[1].dispatchEvent(new Event('mouseenter'));
    await fixture.whenStable();
    expect(el.querySelector('.tooltip')?.textContent).toContain('2026-10-02');
    expect(el.querySelector('.tooltip')?.textContent).toContain('Open 2');

    el.querySelector<HTMLButtonElement>('button.link')!.click();
    await fixture.whenStable();
    const rows = [...el.querySelectorAll('tbody tr')].map((r) =>
      [...r.querySelectorAll('td')].map((td) => td.textContent?.trim()),
    );
    expect(rows).toEqual([
      ['2026-10-01', '4', '0'],
      ['2026-10-02', '2', '2'],
      ['2026-10-03', '0', '0'],
    ]);
  });
});
