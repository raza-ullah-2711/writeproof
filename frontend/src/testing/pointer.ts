/** Builds a PointerEvent with a controlled timeStamp (jsdom stamps events itself). */
export function pointer(
  type: 'pointerdown' | 'pointermove' | 'pointerup' | 'pointercancel',
  init: PointerEventInit & { timeStamp: number; coalesced?: PointerEvent[] },
): PointerEvent {
  const { timeStamp, coalesced, ...rest } = init;
  const event = new PointerEvent(type, {
    bubbles: true,
    cancelable: true,
    pointerId: 1,
    pointerType: 'pen',
    pressure: 0.5,
    ...rest,
  });
  Object.defineProperty(event, 'timeStamp', { value: timeStamp });
  if (coalesced) {
    Object.defineProperty(event, 'getCoalescedEvents', { value: () => coalesced });
  }
  return event;
}

/** A canvas 2D context stand-in; jsdom has no canvas implementation. */
export function fakeContext(): CanvasRenderingContext2D {
  const noop = () => undefined;
  return {
    scale: vi.fn(noop),
    clearRect: vi.fn(noop),
    beginPath: vi.fn(noop),
    moveTo: vi.fn(noop),
    lineTo: vi.fn(noop),
    stroke: vi.fn(noop),
    arc: vi.fn(noop),
    fill: vi.fn(noop),
    fillRect: vi.fn(noop),
  } as unknown as CanvasRenderingContext2D;
}

/** Writes a short three-point stroke on the canvas, starting at `t0` ms. */
export function scribble(canvas: HTMLCanvasElement, t0 = 0): void {
  canvas.dispatchEvent(pointer('pointerdown', { clientX: 10, clientY: 10, timeStamp: t0 }));
  canvas.dispatchEvent(pointer('pointermove', { clientX: 40, clientY: 30, timeStamp: t0 + 200 }));
  canvas.dispatchEvent(pointer('pointerup', { clientX: 60, clientY: 20, timeStamp: t0 + 400 }));
}
