import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { encode } from 'uqr';
import { QrCode } from './qr-code';

@Component({
  imports: [QrCode],
  template: `<app-qr-code [text]="text" label="Your address" />`,
})
class Host {
  text = 'https://writeproof.example/contacts?add=abc';
}

describe('QrCode', () => {
  it('draws one 8px square per dark module', async () => {
    const rects: [number, number][] = [];
    const ctx = { fillStyle: '', fillRect: (x: number, y: number) => rects.push([x, y]) };
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(ctx as never);
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

    const canvas: HTMLCanvasElement = fixture.nativeElement.querySelector('canvas');
    const qr = encode(fixture.componentInstance.text, { ecc: 'M', border: 2 });
    const dark = qr.data.flatMap((row, y) => row.flatMap((d, x) => (d ? [[x * 8, y * 8]] : [])));
    expect(canvas.width).toBe(qr.size * 8);
    expect(canvas.getAttribute('aria-label')).toBe('Your address');
    expect(rects.slice(1)).toEqual(dark); // after the white background
    vi.restoreAllMocks();
  });
});
