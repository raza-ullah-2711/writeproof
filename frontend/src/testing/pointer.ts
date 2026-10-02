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
  } as unknown as CanvasRenderingContext2D;
}
