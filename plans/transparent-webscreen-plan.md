# Transparent WebScreen proof plan

Status: **DIRECT CEF GPU SHARED-TEXTURE + GPU PRESENT VERIFIED; real CEF
alpha composition and automated native input routing VERIFIED; manual visual
acceptance remains READY FOR USER ACCEPTANCE**.
This plan describes the target architecture only; it does not change the
production MCEF backend, default backend, or Gradle dependencies.

The `d992347` high-refresh checkpoint is retained as historical data but is
**SUPERSEDED / INCONCLUSIVE** for modern CEF above 60 Hz: those runs requested
120/144 while configuring `windowless_frame_rate=60`. The current proof records
both requested and configured rates and keeps the CEF 5845 compatibility clamp
while allowing CEF 144 to use 120/144.

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

The current modern CEF 144 result passes gates 1--4 for this isolated proof.
The decoupled three-slot mailbox repeatedly opened
accelerated handles through
D3D11.1 `OpenSharedResource1`, copied into host-owned textures, and presented
at independent 60/120/144 consumer rates over a moving native background.
Producer delivery remained approximately 54--58/s (the 144 run measured 456
copies/published generations at 56.15/s), while the consumer made 1,182
uncoupled presents at 143.97/s. Physical display scanout and human visual
inspection remain **READY FOR USER ACCEPTANCE**; native alpha and input are
covered by the automated matrix below. Do not add a CPU readback workaround.

## Acceptance matrix (2026-08-12)

| Area | Evidence | Result | Boundary |
| --- | --- | --- | --- |
| CEF accelerated callback | Modern CEF 144 `OnAcceleratedPaint`, no CPU `OnPaint`, repeated D3D11.1 open | **AUTOMATED PASS** | Direct proof only |
| GPU mailbox/present | Three host-owned slots, `CopyResource`, independent `Present(0)`, accounting invariant | **AUTOMATED PASS** | Simulator swap chain, not Minecraft |
| Alpha shader math | 5-pixel BGRA premultiplied synthetic source over blue; 5/5 tolerance checks | **SYNTHETIC PASS** | Does not inspect a real CEF frame |
| Alpha end-to-end | Real CEF144 BGRA raw samples plus fixed-blue GPU composition | **AUTOMATED PASS** | Proof-only one-shot/low-frequency staging readback; no per-frame CPU readback |
| Native input routing | Real HWND subclass + native messages; button, checkbox, range, select/focus, Latin text/backspace/arrows, wheel, modal+Escape | **AUTOMATED PASS** | CEF console observations recorded; IME/clipboard not tested |
| Manual visual acceptance | Human confirms world background, edges, rounded corners, overlays, scanout | **READY FOR USER ACCEPTANCE** | Must be performed interactively; no automated PASS |

The exact runner command is bounded or explicitly interactive:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\direct-cef-proof\run-transparent-webscreen.ps1 `
  -CefRoot 'F:\Temp\cef-modern-144\cef_binary_144.0.33+gcb4715c+chromium-144.0.7559.259_windows64' `
  -BuildRoot "$env:TEMP\mcwebui-direct-cef-build" -DurationMs 5000 -TargetHz 60 -AutoInput -AlphaProof
```

For a manual run, use `-DurationMs 0` (the runner passes this explicitly),
omit `-AutoInput`, and close the visible window with Escape. For automated
input, the second Escape closes the modal and then the browser. The runner
always cleans up its local HTTP server in `finally`; no unbounded command is
used in regression tests.

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
  access flags 0. With `--alpha-proof`, CEF receives transparent
  `CefBrowserSettings.background_color`; raw premultiplied BGRA samples pass
  for world reveal and 25/50/75/100% fixed-red patches, and a fixed-blue GPU
  composition pass produces the expected final pixels. This is real CEF
  texture evidence, independent of the retained synthetic alpha field.
- GPU process launch evidence and CEF logs are recorded in result JSON. ANGLE
  backend strings, vendor/device identity, utilization, and actual display
  presentation are **NOT MEASURED**.

- The decoupled mailbox is **VERIFIED** for D3D11 submission: a short
  immediate-context mutex, a three-slot host-owned pool, consumer-slot
  protection, and an independent `Present(0)`/`Present(1)` loop. The producer
  never waits on VSync, a GPU query, or a CPU readback. This is a pacing proof,
  not a claim that CEF can generate 120/144 distinct browser frames.

### High-refresh recheck

At 1280x720 with the same modern CEF 144 accelerated mailbox and external
BeginFrame, configured 60/120/144 produced approximately 54.6/64.3/64.1
published generations per second, while independent `Present(0)` reached
59.9/120.0/144.0. The controlled target-144 A/B was 56.7 published/s at
configured 60 versus 64.0 published/s at configured 144. The result is
**VERDICT B**: the old clamp was a real part of the limit, but this host still
does not produce 120/144 distinct browser generations. Interval summaries and
the exact requested/configured values are recorded in
`plans/direct-cef-runtime-plan.md`.

The automated alpha/input gate is now complete: three bounded CEF144
lifecycles with `DurationMs=0` and `-AutoInput` exited 0 after modal Escape
then browser Escape. Strict JSON parsing found five passing raw CEF samples,
five passing fixed-blue composition samples, `alphaModel=premultiplied`, and
40--41 input observations (64 native messages, 64 CEF dispatches). This
isolated proof is **READY FOR D3D/OpenGL INTEROP PROOF** only; it does not
perform interop, JNI, Minecraft, or production-backend work. Human visual
inspection remains **READY FOR USER VISUAL ACCEPTANCE**, not automated PASS.

The standalone D3D/OpenGL interop gate is also **AUTOMATED PASS on the local
NVIDIA host**. The proof uses `--opengl-interop`, a private WGL context and
top-level hidden window/DC, the existing three-slot D3D mailbox, read-only
`WGL_NV_DX_interop2` registration, explicit lock/unlock ownership, and no CPU
fallback. The full-frame fixed-blue GL composition sampled all five real CEF
alpha points with RGBA mapping and `textureYFlipped=true`; the native input
matrix and cleanup passed. Capability identity was NVIDIA GeForce RTX 5060 Ti,
LUID high 0/low 59869, with WGL_NV_DX_interop and WGL_NV_DX_interop2. AMD/Intel
fallback is not implemented. Target smokes at 60/120/144 and ten bounded
lifecycle runs passed as GL presentation evidence only, never Web FPS claims.
Manual world/rounded-corner/scanout inspection remains **READY FOR USER
ACCEPTANCE**. See `plans/d3d-opengl-interop-plan.md`.

## Future implementation boundary

If a later official CEF/driver combination passes all proof gates, keep the
native API narrow (`initializeRuntime`, `shutdownRuntime`, `createView`,
`destroyView`, `resizeView`, `setVisible`, `requestFrame`, input events,
`acquireLatestFrame`). Do not recreate `CefBrowser`/`CefFrame`/`CefClient` in
Java and do not make Minecraft wait for Chromium.
