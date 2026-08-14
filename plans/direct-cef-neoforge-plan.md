# NeoForge 1.21.1 Direct CEF proof slice

Status: **VERDICT B / opt-in proof slice** (2026-08-13).

This plan covers only the Windows NeoForge 1.21.1 experimental backend. The
default `mcef` backend remains unchanged. The Direct CEF path is selected only
with `mcwebui.browserBackend=direct-cef`; it remains an opt-in Windows proof and
does not package the native CEF runtime.

## Boundaries

- CEF 144.0.33 (`cb4715c47322f31bee2bf2ad9d9add3cf8fc8ea0`) is built as an
  external `mcwebui-direct-cef.dll` plus `mcwebui-cef-helper.exe`.
- The helper is passed explicitly through CEF's
  `browser_subprocess_path`; no subprocess role is assigned to Java.
- Native CEF owns the asynchronous accelerated D3D11 producer and the existing
  three-slot mailbox. NeoForge's render thread is the only GL consumer: it
  must have Minecraft's current WGL context, uses read-only
  `WGL_NV_DX_interop2`, and never creates a private consumer context or calls
  `SwapBuffers`.
- The render surface contract reports `PREMULTIPLIED` alpha and `yFlipped`;
  `NeoForgeMinecraftScreen` uses straight overlay composition with a
  `try/finally` state restore. `isPauseScreen()` is false so the world can
  remain behind the proof surface.
- Resize is deferred while a mailbox slot is registered/locked; the producer
  never releases a registered resource from its callback.
- Direct CEF carries the WebBridge JSON envelopes through CEF message routers.
  By default a process-owned loopback server exposes only the bundled
  `web/playground` resources under an ephemeral port and random capability
  path. `mcwebui.directCef.url` remains an explicit external test override.

## Class-path isolation

CEF is process-global, so loading MCEF/JCEF 116 beside CEF 144 is unsafe. The
bounded proof runner passes `-PmcwebuiDirectCefProof=true`, makes the MCEF mod
dependency optional, and omits `mcef-neoforge` from `runtimeClasspath`. The
direct entrypoint and bootstrap keep MCEF/CEF imports in a separate class so
the direct process can start with only MCWebUI, Minecraft, and NeoForge mods.
The normal run (without that property) still uses stock MCEF.

## Exact bounded runner

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\direct-cef-proof\run-neoforge-webscreen.ps1 `
  -CefRoot "$env:MCWEBUI_CEF_ROOT" `
  -BuildRoot "$env:TEMP\mcwebui-direct-cef-runtime-build" `
  -RuntimeRoot "$env:TEMP\mcwebui-direct-cef-runtime" `
  -CacheRoot "$env:TEMP\mcwebui-direct-cef-cache" `
  -DurationMs 90000 -AutoOpen -TargetHz 60
```

The runner builds the frontend/native artifacts and normally lets Minecraft
serve the bundled page. It waits for the ModDev game process and sound-engine
readiness before starting the requested duration; `DurationMs 0` is a manual
run that lasts until Minecraft closes. `-ExternalPageServer` retains the old
Python-server path for isolated diagnostics. Cleanup always tears down the
game before the optional server. It never stages `web/`, SDKs, caches,
binaries, or result logs.

## Evidence

| Gate | Result | Notes |
| --- | --- | --- |
| Native CEF144 standalone smoke | PASS | `ready=true`, one accelerated callback and published generation, zero copy/lock failures; `interop=UNSUPPORTED` without a current Minecraft WGL context |
| Java/NeoForge compile and tests | PASS | 10 target tests; frontend typecheck/build and all-target build pass |
| Bundled page + real CEF/Java handshake | PASS | Process-owned random loopback URL served the built Vue bundle; renderer bootstrap, CefQuery, and Java WebBridge handshake completed |
| Direct class-path startup | PASS | Mod List was MCWebUI/Minecraft/NeoForge (MCEF absent); backend selection logged `direct-cef`; NVIDIA GL 4.6 startup reached resource loading |
| Direct F8/native surface in Minecraft | USER-VERIFIED / REGRESSION READY | User observed the Direct page and controls in game; automated WGL/fullscreen scanout remains separate |
| Hidden Direct prewarm | PASS | Bounded client run created the native runtime, loaded Vue, completed the Java bridge handshake and produced the first accelerated texture before F8; F8 reused that retained session |
| External-frame pacing | PASS | Game render signals remain independent, while Direct BeginFrame requests use a fractional 60/120/144 Hz cap; 180 host signals deterministically produce 60/120/144 requests |
| Minecraft WGL/D3D/OpenGL mailbox | NOT VERIFIED | Standalone NVIDIA interop proof is separate and must not be called Minecraft integration |
| Human world/alpha/rounded-corner/scanout acceptance | READY FOR USER ACCEPTANCE | Requires an interactive F8 run after the loading screen is gone |

The direct client run remains **Verdict B**: opt-in selection, class-path
isolation, native helper/lifecycle, bundled resource ownership and the real
Vue/CEF/Java bridge are implemented. Production packaging and a repeatable
automated Minecraft WGL/fullscreen matrix are still pending; do not infer
production readiness from this checkpoint.

## Lifecycle and ownership notes

CEF initialization and shutdown are owned by the thread that creates the native
runtime. Browser close is asynchronous and bounded; the native destructor does
not unload `libcef.dll` while Chromium callbacks may still drain. Java close is
idempotent and screen close is the only owner of the surface. A future runtime
integration must add an explicit owner-thread gate and a render-thread
registration/unregistration handshake before shipping this backend.

Direct prewarm deliberately runs on the normal Minecraft client/render tick;
moving CEF/WGL initialization to a worker thread would violate that ownership
contract. It keeps the CEF browser visible internally but the Minecraft view
hidden and unfocused until an accelerated texture plus bridge handshake exists,
then calls `WasHidden(true)`. The one-time initialization cost is therefore paid
during client loading rather than on the first F8. External BeginFrame requests
use the configured Direct target instead of blindly following a 180+ Hz game
render clock; the game signal rate, browser rAF callbacks, and newly published
GPU generations remain separate metrics and must not be labeled as one FPS.

## Hidden idle footprint and opacity control

After prewarm completes, the retained browser is hidden with `WasHidden(true)`
and the Java lifecycle stops issuing external BeginFrame requests. In that
state there is no continuous accelerated paint, host `CopyResource`, or WGL
lock/unlock work. The remaining steady-state work is one bounded empty bridge
poll per client tick plus idle CEF/helper and loopback-server threads. This is
expected to have very small CPU/frame-time cost, but exact host CPU and memory
remain an interactive measurement rather than a claimed benchmark.

The deliberate idle tradeoff is memory. At the observed 2560x1418 viewport, one
BGRA mailbox texture is 14,520,320 bytes (13.85 MiB); the active three-slot host
mailbox can reach 41.54 MiB before CEF compositor/shared-texture allocations.
Releasing the retained browser or textures after a timeout could reclaim that
memory, but would restore the cold first-F8 initialization hitch. The current
proof therefore favors fast resume. The playground now also stops a running
Animation Lab when CEF hides the document, preventing its diagnostic interval
from remaining active after ESC.

The normal showcase exposes a fixed `WebScreen opacity` slider from 0 to 100%.
It applies one group opacity to the page background and UI while leaving the
control itself visible at 0%, so the user can always restore it. In Direct CEF
this reveals the live Minecraft world through the already verified
premultiplied-alpha path; final visual appearance remains a manual F8 check.
