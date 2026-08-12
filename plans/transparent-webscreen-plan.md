# Transparent WebScreen proof plan

Status: **DIRECT CEF GPU SHARED-TEXTURE + GPU PRESENT VERIFIED; visual alpha
and input acceptance remain NOT TESTED**.
This plan describes the target architecture only; it does not change the
production MCEF backend, default backend, or Gradle dependencies.

## Product contract

MCWebUI is a Vue/TypeScript/HTML replacement UI for Minecraft screen-space
screens. The intended presentation is:

```text
Minecraft world framebuffer
        +
transparent GPU browser UI surface
        =
buttons / slider / scroll / input / modal over the world
```

The Minecraft render loop owns presentation of the latest complete browser
frame. It must not synchronously request a browser frame and wait for CEF.
When no new browser frame is ready, the host keeps presenting the latest
host-owned texture.

## Proof gates

The isolated direct CEF executable must satisfy all of these before any JNI or
Minecraft integration work is authorized:

1. `OnAcceleratedPaint` is repeatedly observed with no CPU `OnPaint` fallback.
2. The callback handle can be opened with the documented D3D11 path and a
   texture descriptor is recorded (size, format, sample count, usage/bind
   flags, and handle lifecycle).
3. A native simulator composites that texture over a moving D3D11 background,
   with transparent, semi-transparent, and opaque browser regions visibly
   correct. No per-frame CPU readback is permitted.
4. Real mouse move/button/wheel and keyboard/text input reach the OSR browser;
   range drag, scroll, button, checkbox/select, and text input are exercised.
5. Requests, browser rAF, accelerated callbacks, and D3D-presented frames are
   measured separately at 60/120/144 targets with median/P95/P99 or max jank.

The current modern CEF 144 result passes gates 1--3 for GPU submission: the
decoupled three-slot mailbox repeatedly opened accelerated handles through
D3D11.1 `OpenSharedResource1`, copied into host-owned textures, and presented
at independent 60/120/144 consumer rates over a moving native background.
Producer delivery remained approximately 54--58/s (the 144 run measured 456
copies/published generations at 56.15/s), while the consumer made 1,182
uncoupled presents at 143.97/s. Visual alpha inspection, real input, and
physical display scanout remain **NOT TESTED**. Do not claim the full
transparent WebScreen route until those checks pass; do not add a CPU readback
workaround.

## Current isolated findings

- CEF 5845 remains the historical CPU baseline: backend default is about 30
  paint/s; External BeginFrame improves to about 55--57 paint/s but does not
  provide 120/144 output; `OnAcceleratedPaint` remained zero.
- Official modern CEF 144 compiles and loads the real playground bundle. The
  windowed reference observed 180 Hz rAF in one run but a later run observed
  zero rAF, so WINDOWED_BASELINE is **NOT RUNTIME VERIFIED**.
- Modern CPU OSR reproduces the approximately 60 paint/s ceiling at 120/144
  requests. Modern accelerated OSR delivers callbacks without CPU paint, and
  D3D11.1 shared-resource opening/present is verified in the simulator.
- The mailbox JSON records CEF color type 1 (`CEF_COLOR_TYPE_BGRA_8888`) and
  exact D3D descriptor format 87 (`DXGI_FORMAT_B8G8R8A8_UNORM`), with CPU
  access flags 0. This is resource metadata, not an alpha-pixel result.
- GPU process launch evidence and CEF logs are recorded in result JSON. ANGLE
  backend strings, vendor/device identity, utilization, and actual display
  presentation are **NOT MEASURED**.

- The decoupled mailbox is **VERIFIED** for D3D11 submission: a short
  immediate-context mutex, a three-slot host-owned pool, consumer-slot
  protection, and an independent `Present(0)`/`Present(1)` loop. The producer
  never waits on VSync, a GPU query, or a CPU readback. This is a pacing proof,
  not a claim that CEF can generate 120/144 distinct browser frames.

## Future implementation boundary

If a later official CEF/driver combination passes all proof gates, keep the
native API narrow (`initializeRuntime`, `shutdownRuntime`, `createView`,
`destroyView`, `resizeView`, `setVisible`, `requestFrame`, input events,
`acquireLatestFrame`). Do not recreate `CefBrowser`/`CefFrame`/`CefClient` in
Java and do not make Minecraft wait for Chromium.
