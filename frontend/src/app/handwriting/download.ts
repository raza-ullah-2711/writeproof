import { HandwritingSample, toJson } from './handwriting-sample';

export function sampleFileName(sample: HandwritingSample): string {
  return `handwriting-${sample.capturedAt.replace(/[:.]/g, '-')}.json`;
}

/** Saves the sample as a JSON file in the browser. Nothing is uploaded. */
export function downloadSample(sample: HandwritingSample): void {
  const url = URL.createObjectURL(new Blob([toJson(sample)], { type: 'application/json' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = sampleFileName(sample);
  link.click();
  URL.revokeObjectURL(url);
}
