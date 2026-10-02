# Handwriting capture (Task 3)

`HandwritingPad` (`frontend/src/app/handwriting/`) records handwriting as
**stroke dynamics**, never as an image (architecture rule 3). Try it at
`/capture`: write, replay at the original speed, export or import JSON.

## Sample format (`writeproof.handwriting` v1)

```jsonc
{
  "format": "writeproof.handwriting",
  "version": 1,
  "capturedAt": "2026-10-02T19:07:13.265Z", // first pen-down
  "device": "pen", // "pen" | "touch" | "mouse"
  "width": 600, // pad coordinate space
  "height": 240,
  "strokes": [
    [
      { "x": 56.07, "y": 111.63, "t": 0, "pressure": 0.2, "penDown": true },
      { "x": 61.2, "y": 108.9, "t": 8.1, "pressure": 0.27, "penDown": true },
      // ...
      { "x": 540.1, "y": 120.4, "t": 1290.5, "pressure": 0, "penDown": false },
    ],
  ],
}
```

- `x`, `y`: pad units (0..`width`, 0..`height`), independent of the on-screen
  size and device pixel ratio. 2 decimal places.
- `t`: milliseconds since the first pen-down of the sample, from
  `PointerEvent.timeStamp`. A single timeline across strokes, so the gaps
  between strokes (pen-up time) are kept. Never decreases. 0.1 ms resolution.
- `pressure`: 0..1. **Only meaningful when `device` is `pen`**. Mice and
  most touch screens report a constant 0.5 while in contact. Verification
  (Task 4) must not treat constant pressure as a liveness signal on those devices.
- `penDown`: `true` for every point while in contact. The **last point of each
  stroke is the pen-up** (`false`). `parseSample` enforces this.

## Capture details

- Pointer Events with `getCoalescedEvents()`, so fast strokes keep the full
  hardware sampling rate instead of one point per animation frame.
- One pointer at a time. A second finger or a resting palm can't inject
  points into the stroke in progress. Non-primary mouse buttons are ignored.
- `pointercancel` ends the stroke with a pen-up at the last known position.
- `touch-action: none` on the canvas, so touch input draws instead of
  scrolling the page.
- Export happens in the browser (Blob download). Nothing is uploaded yet.
  Import validates the file with `parseSample` and loads it for replay. Exported
  samples double as fixtures for Task 4.
