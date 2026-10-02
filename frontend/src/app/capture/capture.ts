import { Component, computed, signal, viewChild } from '@angular/core';
import { downloadSample } from '../handwriting/download';
import { HandwritingPad } from '../handwriting/handwriting-pad';
import { parseSample, pointCount, sampleDuration } from '../handwriting/handwriting-sample';

/** Capture playground: write, replay at original speed, export or import JSON. */
@Component({
  selector: 'app-capture',
  imports: [HandwritingPad],
  templateUrl: './capture.html',
  styleUrl: './capture.scss',
})
export class Capture {
  protected readonly pad = viewChild.required(HandwritingPad);
  protected readonly importError = signal<string | null>(null);

  protected readonly stats = computed(() => {
    const sample = this.pad().sample();
    return sample
      ? {
          strokes: sample.strokes.length,
          points: pointCount(sample),
          seconds: (sampleDuration(sample) / 1000).toFixed(2),
          device: sample.device,
        }
      : null;
  });

  protected exportJson(): void {
    const sample = this.pad().sample();
    if (sample) {
      downloadSample(sample);
    }
  }

  protected async importJson(input: HTMLInputElement): Promise<void> {
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    try {
      this.pad().load(parseSample(await file.text()));
      this.importError.set(null);
    } catch (e) {
      this.importError.set(e instanceof Error ? e.message : 'Could not read that file.');
    }
  }
}
