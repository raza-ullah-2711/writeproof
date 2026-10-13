/**
 * Prepares a photo of a paper letter for the archive, in the browser, before anything is
 * encrypted or uploaded: turned upright, scaled to at most `MAX_SIDE`, and re-encoded as a plain
 * JPEG. Re-encoding through a canvas drops everything but the pixels: no location, camera or
 * date metadata (EXIF) ever leaves the device.
 */

export const MAX_SIDE = 2400;
export const MAX_PHOTO_BYTES = 1536 * 1024;

export interface PreparedPhoto {
  bytes: Uint8Array<ArrayBuffer>;
  width: number;
  height: number;
}

const QUALITIES = [0.86, 0.78, 0.7, 0.6, 0.5];

export async function preparePhoto(file: Blob): Promise<PreparedPhoto> {
  if (!file.type.startsWith('image/')) {
    throw new Error('That file is not an image');
  }
  const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
  try {
    let side = Math.min(MAX_SIDE, Math.max(bitmap.width, bitmap.height));
    for (let attempt = 0; attempt < 4; attempt++) {
      const scale = side / Math.max(bitmap.width, bitmap.height);
      const width = Math.max(1, Math.round(bitmap.width * scale));
      const height = Math.max(1, Math.round(bitmap.height * scale));
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext('2d');
      if (!ctx) {
        throw new Error('This browser cannot prepare photos');
      }
      ctx.fillStyle = '#ffffff'; // JPEG has no transparency
      ctx.fillRect(0, 0, width, height);
      ctx.drawImage(bitmap, 0, 0, width, height);
      for (const quality of QUALITIES) {
        const jpeg = await toJpeg(canvas, quality);
        if (jpeg.size <= MAX_PHOTO_BYTES) {
          return { bytes: new Uint8Array(await jpeg.arrayBuffer()), width, height };
        }
      }
      side = Math.round(side * 0.8);
    }
    throw new Error('This photo is too large even after shrinking it');
  } finally {
    bitmap.close();
  }
}

function toJpeg(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  return new Promise((resolve, reject) =>
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('Could not encode the photo'))),
      'image/jpeg',
      quality,
    ),
  );
}

/** A `data:` URL for showing a JPEG (the app's CSP allows data: images, not blob: URLs). */
export function jpegDataUrl(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return `data:image/jpeg;base64,${btoa(binary)}`;
}
