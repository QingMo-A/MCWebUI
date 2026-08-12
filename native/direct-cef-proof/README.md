# Direct CEF runtime proof

This isolated Windows amd64 executable uses the pinned CEF
`116.0.27+gd8c85ac+chromium-116.0.5845.190` (`5845`) C++ API directly. It does
not load MCEF, JCEF, JNI, Minecraft, or the production browser adapter.

The proof loads the real `frontend/playground/dist/index.html` with `file://`
and records three independent clocks:

- requested External BeginFrames;
- browser `requestAnimationFrame` aggregates reported once per second;
- CPU `OnPaint` and optional `OnAcceleratedPaint` callbacks.

Supported modes are `windowed-baseline`, `backend-default`, and
`external-begin-frame`. Target rates
30, 60, 120, and 144 are requests, never reported as browser FPS. `--idle`
keeps the rAF observer running but stops the injected visual mutation so idle
paint suppression can be measured. `--accelerated` requests the CEF shared-
texture path and records `OnAcceleratedPaint` handles. `--simulator-coupled`
retains the historical callback-owned path: it opens each handle with
D3D11.1 `OpenSharedResource1`, samples it in a GPU-only fullscreen shader over
a moving native background, and calls `Present(1)` from that callback.
`--simulator-mailbox` uses the decoupled proof: a three-slot host-owned
D3D11 texture mailbox copies/publishes in the callback and an independent
consumer presents the latest complete slot using `Present(0)` for
`--present-mode=uncoupled` or `Present(1)` for the VSync reference. The
producer uses a non-blocking immediate-context lock and never waits on a
query, VSync, or CPU readback.

`windowed-baseline` uses a normal native CEF popup (no OSR) as a browser
compositor/rAF reference. It does not produce `OnPaint` or
`OnAcceleratedPaint`; a zero render surface is expected for this mode.

The result JSON includes `gpuDiagnostics` from CEF child-process callbacks
(GPU/renderer launches, filtered GPU switches, and accelerated/D3D outcomes).
This is diagnostics evidence, not a claim of GPU utilization or screen
presentation. The modern CEF profile and current measured blocker are recorded
in `plans/direct-cef-runtime-plan.md` and
`plans/transparent-webscreen-plan.md`.

CEF SDK/runtime files, CMake output, logs, caches, and result JSON must remain
outside the repository. The helper scripts default to `%TEMP%` and reuse the
pinned SDK downloader under `scripts/frame-proof`.

```powershell
Set-ExecutionPolicy -Scope Process Bypass
$cef = .\scripts\direct-cef-proof\prepare-direct-cef-proof.ps1
$exe = .\scripts\direct-cef-proof\build-direct-cef-proof.ps1 -CefRoot $cef
.\scripts\direct-cef-proof\run-direct-cef-proof.ps1 -Executable $exe -IncludeAccelerated -IncludeSimulator
```

The executable uses `CefExecuteProcess`, a same-executable subprocess model,
`multi_threaded_message_loop`, a hidden Windows host for OSR, and a normal
popup host for `windowed-baseline`.
It writes CEF cache/log files below `%TEMP%\mcwebui-direct-cef-runtime` and one
aggregate JSON result per run. Mailbox JSON also reports GPU copy/completion,
published generations, consumer iterations, successful presents, generation
classification, and a `presentationAccounting` invariant. No per-frame
logging or disk I/O occurs in the render callbacks.

This is feasibility infrastructure, not a production backend. See
`plans/direct-cef-runtime-plan.md` for measured results and the integration
decision.
