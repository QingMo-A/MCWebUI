# NeoForge 1.21.1 Direct CEF proof slice

Status: **VERDICT B / opt-in proof slice** (2026-08-13).

This plan covers only the Windows NeoForge 1.21.1 experimental backend. The
default `mcef` backend remains unchanged. The Direct CEF path is selected only
with `mcwebui.browserBackend=direct-cef` and a proof URL; it does not implement
the production bridge, Minecraft JNI interop, or a packaged native runtime.

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
- Bridge/WebBridge transport is **NOT IMPLEMENTED** on this path. The proof
  URL is ordinary local HTTP (`mcwebui.directCef.url`), not `mcui://`.

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

The runner starts a local HTTP server, builds the frontend/native artifacts,
passes the DLL/helper/cache paths to Gradle, waits for a non-`Loading` Minecraft
window before posting F8, and performs bounded child-process cleanup. It never
stages `web/`, SDKs, caches, binaries, or result logs.

## Evidence

| Gate | Result | Notes |
| --- | --- | --- |
| Native CEF144 standalone smoke | PASS | `ready=true`, one accelerated callback and published generation, zero copy/lock failures; `interop=UNSUPPORTED` without a current Minecraft WGL context |
| Java/NeoForge compile and tests | PASS | 10 target tests; frontend typecheck/build and all-target build pass |
| Direct class-path startup | PASS | Mod List was MCWebUI/Minecraft/NeoForge (MCEF absent); backend selection logged `direct-cef`; NVIDIA GL 4.6 startup reached resource loading |
| Direct F8/native surface in Minecraft | NOT VERIFIED | The bounded run observed only the loading-window title before watchdog cleanup; no native diagnostics were produced |
| Minecraft WGL/D3D/OpenGL mailbox | NOT VERIFIED | Standalone NVIDIA interop proof is separate and must not be called Minecraft integration |
| Human world/alpha/rounded-corner/scanout acceptance | READY FOR USER ACCEPTANCE | Requires an interactive F8 run after the loading screen is gone |

The direct client run therefore remains **Verdict B**: the opt-in selection,
class-path isolation, native helper/lifecycle smoke, and Java surface slice are
implemented, but full Minecraft F8/native/GL evidence is still pending. Do not
claim production readiness, JNI bridge completion, or a successful visual
acceptance from this checkpoint.

## Lifecycle and ownership notes

CEF initialization and shutdown are owned by the thread that creates the native
runtime. Browser close is asynchronous and bounded; the native destructor does
not unload `libcef.dll` while Chromium callbacks may still drain. Java close is
idempotent and screen close is the only owner of the surface. A future runtime
integration must add an explicit owner-thread gate and a render-thread
registration/unregistration handshake before shipping this backend.

