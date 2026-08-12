# Direct CEF runtime proof

This isolated Windows amd64 executable uses the pinned CEF
`116.0.27+gd8c85ac+chromium-116.0.5845.190` (`5845`) C++ API directly. It does
not load MCEF, JCEF, JNI, Minecraft, or the production browser adapter.

The proof loads the real `frontend/playground/dist/index.html` with `file://`
and records three independent clocks:

- requested External BeginFrames;
- browser `requestAnimationFrame` aggregates reported once per second;
- CPU `OnPaint` and optional `OnAcceleratedPaint` callbacks.

Supported modes are `backend-default` and `external-begin-frame`. Target rates
30, 60, 120, and 144 are requests, never reported as browser FPS. `--idle`
keeps the rAF observer running but stops the injected visual mutation so idle
paint suppression can be measured. `--accelerated` requests the CEF 5845
shared-texture path and attempts `ID3D11Device::OpenSharedResource` only if an
actual accelerated callback supplies a handle.

CEF SDK/runtime files, CMake output, logs, caches, and result JSON must remain
outside the repository. The helper scripts default to `%TEMP%` and reuse the
pinned SDK downloader under `scripts/frame-proof`.

```powershell
Set-ExecutionPolicy -Scope Process Bypass
$cef = .\scripts\direct-cef-proof\prepare-direct-cef-proof.ps1
$exe = .\scripts\direct-cef-proof\build-direct-cef-proof.ps1 -CefRoot $cef
.\scripts\direct-cef-proof\run-direct-cef-proof.ps1 -Executable $exe -IncludeAccelerated
```

The executable uses `CefExecuteProcess`, a same-executable subprocess model,
`multi_threaded_message_loop`, a hidden Windows host, and OSR browser creation.
It writes CEF cache/log files below `%TEMP%\mcwebui-direct-cef-runtime` and one
aggregate JSON result per run. No per-frame logging or disk I/O occurs in the
render callbacks.

This is feasibility infrastructure, not a production backend. See
`plans/direct-cef-runtime-plan.md` for measured results and the integration
decision.
