import { describe, expect, it, vi } from "vitest";
import { createFramePacer } from "./frame-pacing";

describe("frame pacer lifecycle", () => {
  it("starts once and stops without leaving an animation callback", () => {
    let queued: FrameRequestCallback | undefined;
    const raf = vi.fn((cb: FrameRequestCallback) => { queued = cb; return 7; });
    const cancel = vi.fn();
    const frame = vi.fn();
    const pacer = createFramePacer(frame, { requestAnimationFrame: raf, cancelAnimationFrame: cancel });
    pacer.start(); pacer.start();
    expect(raf).toHaveBeenCalledTimes(1);
    queued?.(1);
    expect(frame).toHaveBeenCalledTimes(1);
    pacer.stop();
    expect(cancel).toHaveBeenCalledWith(7);
    expect(pacer.running()).toBe(false);
  });
});
