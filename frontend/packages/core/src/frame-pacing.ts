export type FramePacer = {
  start(): void;
  stop(): void;
  running(): boolean;
};
export type FrameScheduler = Pick<typeof globalThis, "requestAnimationFrame" | "cancelAnimationFrame">;

/** Small rAF lifecycle helper; it never starts until explicitly requested. */
export function createFramePacer(onFrame: () => void, scheduler: FrameScheduler = globalThis): FramePacer {
  let handle: number | undefined;
  const tick = () => {
    handle = scheduler.requestAnimationFrame(() => {
      onFrame();
      if (handle !== undefined) tick();
    });
  };
  return {
    start() { if (handle === undefined) tick(); },
    stop() { if (handle !== undefined) { scheduler.cancelAnimationFrame(handle); handle = undefined; } },
    running() { return handle !== undefined; },
  };
}
