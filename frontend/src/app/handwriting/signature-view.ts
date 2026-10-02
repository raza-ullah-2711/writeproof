import { Component, effect, input, viewChild } from '@angular/core';
import { HandwritingPad } from './handwriting-pad';
import { HandwritingSample } from './handwriting-sample';

/** A received handwritten signature: shown as written, and replayable at its original speed. */
@Component({
  selector: 'app-signature-view',
  imports: [HandwritingPad],
  template: `
    <app-handwriting-pad [readonly]="true" [width]="sample().width" [height]="sample().height" />
    <button type="button" class="replay" (click)="pad().replay()" [disabled]="pad().replaying()">
      Replay signature
    </button>
  `,
  styles: `
    :host {
      display: block;
      max-width: 22rem;
    }
    .replay {
      margin-top: 0.25rem;
    }
  `,
})
export class SignatureView {
  readonly sample = input.required<HandwritingSample>();
  protected readonly pad = viewChild.required(HandwritingPad);

  constructor() {
    effect(() => this.pad().load(this.sample()));
  }
}
