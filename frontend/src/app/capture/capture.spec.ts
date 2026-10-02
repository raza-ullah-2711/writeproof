import { TestBed } from '@angular/core/testing';
import { fakeContext, pointer } from '../../testing/pointer';
import { toJson } from '../handwriting/handwriting-sample';
import { Capture } from './capture';

describe('Capture', () => {
  beforeEach(() => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(fakeContext() as never);
    vi.spyOn(HTMLCanvasElement.prototype, 'getBoundingClientRect').mockReturnValue(
      new DOMRect(0, 0, 600, 240),
    );
  });

  afterEach(() => vi.restoreAllMocks());

  async function render() {
    const fixture = TestBed.createComponent(Capture);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const button = (label: string) =>
      [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!;
    return { fixture, el, button };
  }

  async function write(fixture: { whenStable(): Promise<unknown> }, el: HTMLElement) {
    const canvas = el.querySelector('canvas')!;
    canvas.dispatchEvent(pointer('pointerdown', { clientX: 10, clientY: 10, timeStamp: 0 }));
    canvas.dispatchEvent(pointer('pointermove', { clientX: 20, clientY: 15, timeStamp: 250 }));
    canvas.dispatchEvent(pointer('pointerup', { clientX: 20, clientY: 15, timeStamp: 500 }));
    await fixture.whenStable();
  }

  it('disables actions until something is written, then shows stats', async () => {
    const { fixture, el, button } = await render();
    expect(button('Export JSON').disabled).toBe(true);

    await write(fixture, el);

    expect(button('Export JSON').disabled).toBe(false);
    expect(el.querySelector('.stats')?.textContent).toMatch(/1 strokes.*3 points.*0\.50 s.*pen/s);
  });

  it('exports the sample as a JSON download', async () => {
    const { fixture, el, button } = await render();
    await write(fixture, el);
    const createUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:sample');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined);

    button('Export JSON').click();

    expect(click).toHaveBeenCalledOnce();
    const anchor = click.mock.contexts[0] as HTMLAnchorElement;
    expect(anchor.download).toMatch(/^handwriting-.*\.json$/);
    const blob = createUrl.mock.calls[0][0] as Blob;
    const exported = JSON.parse(await blob.text());
    expect(exported.format).toBe('writeproof.handwriting');
    expect(exported.strokes[0]).toHaveLength(3);
  });

  it('imports an exported sample, and reports invalid files', async () => {
    const { fixture, el } = await render();
    const input = el.querySelector<HTMLInputElement>('input[type=file]')!;
    const choose = async (contents: string) => {
      Object.defineProperty(input, 'files', {
        configurable: true,
        value: [new File([contents], 'sample.json', { type: 'application/json' })],
      });
      input.dispatchEvent(new Event('change'));
      await vi.waitFor(async () => {
        await fixture.whenStable();
        if (!el.querySelector('.stats, .error')) throw new Error('not yet');
      });
    };

    await choose('{"nope":true}');
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Not a Writeproof');

    await choose(
      toJson({
        format: 'writeproof.handwriting',
        version: 1,
        capturedAt: '2026-10-02T12:00:00.000Z',
        device: 'touch',
        width: 600,
        height: 240,
        strokes: [
          [
            { x: 1, y: 1, t: 0, pressure: 0.5, penDown: true },
            { x: 2, y: 2, t: 1500, pressure: 0, penDown: false },
          ],
        ],
      }),
    );
    expect(el.querySelector('.stats')?.textContent).toMatch(/1 strokes.*2 points.*1\.50 s.*touch/s);
    expect(el.querySelector('[role=alert]')).toBeNull();
  });
});
