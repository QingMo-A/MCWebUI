# NeoForge 1.21.1 Direct CEF proof slice

Status: **VERDICT B / opt-in experimental runtime** (2026-08-14).

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

## Runtime discovery boundary (Phase A)

The Direct backend no longer loads arbitrary native/helper paths from a class
initializer. `NeoForgeWebSession` first resolves the fixed project requirement,
discovers either the explicit `mcwebui.directCef.runtimeDir` override or the
standard instance directory, parses `runtime.json`, validates the complete file
tree plus size/SHA-256, and only then passes a `ValidatedDirectCefRuntime` to
the process-global loader. A bad explicit override fails visibly and never
falls through to another runtime or to MCEF.

The standard location is
`<instance>/mcwebui/runtime/cef/cef-144.0.33-cb4715c/windows-x86_64`.
Mutable cache data defaults to
`<instance>/mcwebui/cache/cef/cef-144.0.33-cb4715c`, outside the immutable
runtime tree. Phase A supports a prepared directory only; ZIP import and
automatic download are not implemented.

## Exact bounded runner

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\direct-cef-proof\run-neoforge-webscreen.ps1 `
  -CefRoot "$env:MCWEBUI_CEF_ROOT" `
  -BuildRoot "$env:TEMP\mcwebui-direct-cef-runtime-build" `
  -RuntimeRoot "$env:TEMP\mcwebui-direct-cef-instance" `
  -RuntimeSource Standard `
  -DurationMs 90000 -AutoOpen -TargetHz 60
```

For the explicit-override regression, use a new empty `RuntimeRoot` and add
`-RuntimeSource Override`. The runner assembles a prepared directory, generates
its deterministic manifest, and launches through discovery/validation/loading;
it does not pass raw native or helper file paths.

The 2026-08-14 Phase A standard and override runs both loaded the bundled Vue
page, completed one Java bridge handshake, and completed hidden prewarm with two
accelerated/published generations. Render lease and WGL lock/unlock invariants
were balanced with zero registration/lock failures. These bounded runs verified
startup/discovery/prewarm; they did not replace the existing user visual
acceptance checkpoint.

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
| Direct F8/native surface in Minecraft | USER RUNTIME VERIFIED | User observed the Direct page, bridge and controls in game; repeatable automated world/F8/fullscreen regression remains pending |
| Hidden Direct prewarm | PASS | Bounded client run created the native runtime, loaded Vue, completed the Java bridge handshake and produced the first accelerated texture before F8; F8 reused that retained session |
| External-frame pacing | PASS | Game render signals remain independent, while Direct BeginFrame requests use a fractional 60/120/144 Hz cap; 180 host signals deterministically produce 60/120/144 requests |
| Minecraft WGL/D3D/OpenGL mailbox | USER RUNTIME VERIFIED / AUTOMATED REGRESSION PENDING | A real Direct CEF texture was consumed and drawn by the Minecraft Screen on this NVIDIA host; one-shot native markers and counters now distinguish context/device/registration/lease/draw evidence, but an automated world/fullscreen matrix is still pending |
| Human world/alpha/rounded-corner/scanout acceptance | READY FOR USER ACCEPTANCE | Requires an interactive F8 run after the loading screen is gone |

The direct client run remains **Verdict B**: opt-in selection, class-path
isolation, native helper/lifecycle, bundled resource ownership and the real
Vue/CEF/Java bridge are implemented. Production packaging and a repeatable
automated Minecraft WGL/fullscreen matrix are still pending; do not infer
production readiness from this checkpoint.

## Premultiplied composition and render lease

The Direct CEF texture contract is `PREMULTIPLIED`: source RGB already contains
the source alpha multiplication. Minecraft therefore uses the typed target-local
blend policy `ONE / ONE_MINUS_SRC_ALPHA` for RGB and alpha. The previous
`SRC_ALPHA / ONE_MINUS_SRC_ALPHA` RGB factors multiplied translucent CEF color
twice and could darken panels, text antialiasing, rounded corners and shadows.
The MCEF surface remains `OPAQUE` with blending disabled. These semantics live
only in the NeoForge surface capability and do not leak texture/OpenGL types
into common.

Once `beginRenderFrame()` succeeds, texture lookup (including `textureId <= 0`),
Minecraft state setup, buffer creation and the shader draw all live inside one
outer `try/finally`; `endRenderFrame()` is the sole release path. Render state
restoration resets the alpha policy, shader texture and depth state before that
outer release. A successful `BufferUploader.drawWithShader` explicitly marks
the native generation as drawn; acquiring a WGL texture is not mislabeled as a
Minecraft draw.

## Runtime evidence and invariants

Native counters are primitive atomics and diagnostics are sampled only at
prewarm completion, hide/shutdown or an explicit evidence request. They expose:

- interop device open state, current registered slots and registration failures;
- render begin attempts/successes/ends;
- interop locks/unlocks and their failures;
- published, newly drawn and repeated generations, producer drops and current
  generation;
- resize and actual HGLRC/HDC context-refresh counts.

One-shot markers identify the first Minecraft GL context, interop device,
mailbox registration, render lease and successful Screen draw. A result may be
called PASS only when `renderBeginSuccesses == renderEnds`,
`interopLocks == interopUnlocks`, and registration/lock/unlock failures are all
zero. The runner's optional `-CollectEvidence` writes a bounded evidence JSON;
absence of a first real draw is `USER_ACTION_REQUIRED`, never an inferred PASS.

## Lifecycle and ownership notes

CEF initialization and shutdown are owned by the thread that creates the native
runtime. Browser close is asynchronous and bounded; the native destructor does
not unload `libcef.dll` while Chromium callbacks may still drain. Java close is
idempotent, the retained session owns the surface, and Screen close only hides
and detaches it. Native CEF lifecycle calls have an owner-thread gate; WGL
registration and lease operations run on Minecraft's render thread with the
current HGLRC/HDC identity. A future multi-WebView process service still needs
to centralize process-global CEF initialization and shutdown before this can be
called production-ready.

Ownership is deliberately split into three scopes:

- **PROCESS RUNTIME**: process-global CEF initialization, subprocess/helper and
  native libraries. The current experiment approximates this with one retained
  Direct session; future WebViews must not each call `CefInitialize`.
- **SESSION / VIEW**: browser, bridge session, D3D mailbox and bundled loopback
  resource server.
- **SCREEN ATTACHMENT**: visibility, focus, input ownership and the Minecraft
  render lease. ESC detaches/hides the Screen without destroying the warm
  session.

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

## Context recreation

`refreshGlContext()` is a hint to re-sample the current WGL identity, not an
instruction to rebuild interop on every GUI resize. The native render thread
compares both `wglGetCurrentContext()` and `wglGetCurrentDC()`. Only an actual
identity change abandons/unregisters old-context objects, opens a new interop
device and re-registers mailbox slots. Rebind and resize replacement skip the
transition frame so teardown and `wglDXLockObjectsNV` never occur in the same
fullscreen-change opportunity. Automated Minecraft window/fullscreen cycling
remains pending even though the user runtime path is established.

## Future runtime budget matrix

No total Direct CEF CPU/GPU-memory number is claimed yet. A future benchmark
must measure the following states and viewports with the same tools and sampling
window:

| Runtime state | 1280x720 | 1920x1080 | 2560x1440 / nearest current |
| --- | --- | --- | --- |
| Cold runtime | process private/working set, child count, startup time | same | same |
| Warm hidden runtime | CPU idle, frame-time impact, dedicated/shared GPU memory if reliable | same | same |
| Visible static WebScreen | CPU, frame-time, CEF child count, GPU memory | same | same |
| Visible animated WebScreen | CPU, frame-time distribution, GPU memory | same | same |

The known host-texture budget remains 13.85 MiB for one 2560x1418 BGRA texture
and 41.54 MiB for three slots. It excludes the CEF compositor/source texture,
Minecraft framebuffer and driver allocations. Future idle policies may be
`KEEP_WARM`, `RELEASE_AFTER_TIMEOUT` or `AGGRESSIVE_RELEASE`; this checkpoint
keeps `KEEP_WARM` and does not implement a policy system. See
`plans/direct-cef-distribution-plan.md` for the separate design-only runtime
delivery boundary.
