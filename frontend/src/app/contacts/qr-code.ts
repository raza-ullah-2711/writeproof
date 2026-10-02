import { Component, ElementRef, effect, input, viewChild } from '@angular/core';
import { encode } from 'uqr';

/** Draws `text` as a QR code on a canvas (no HTML strings, so it works under Trusted Types). */
@Component({
  selector: 'app-qr-code',
  template: `<canvas #canvas role="img" [attr.aria-label]="label()"></canvas>`,
  styles: `
    canvas {
      display: block;
      image-rendering: pixelated;
      width: 100%;
      max-width: 240px;
      height: auto;
    }
  `,
})
export class QrCode {
  readonly text = input.required<string>();
  readonly label = input('QR code');

  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');

  constructor() {
    effect(() => {
      const qr = encode(this.text(), { ecc: 'M', border: 2 });
      const scale = 8;
      const canvas = this.canvas().nativeElement;
      canvas.width = canvas.height = qr.size * scale;
      const ctx = canvas.getContext('2d');
      if (!ctx) {
        return;
      }
      ctx.fillStyle = '#fff';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.fillStyle = '#000';
      qr.data.forEach((row, y) =>
        row.forEach((dark, x) => dark && ctx.fillRect(x * scale, y * scale, scale, scale)),
      );
      canvas.dataset['modules'] = String(qr.size);
    });
  }
}
