# Direct CEF runtime proof

Status: **VERIFIED CPU OSR; VERIFIED external pacing improvement with an
approximately 60 Hz browser/paint ceiling; FAILED accelerated callback on this
CEF 5845 machine**.

## 1. Motivation

MCWebUI's durable assets are the Vue/TypeScript frontend, common bridge, and
backend-neutral browser contract. MCEF/JCEF remains the production experiment,
but its Java wrapper obscures whether the observed ~30 Hz paint cadence comes
from CEF or the wrapper. This proof removes Minecraft, MCEF, JCEF, and JNI and
uses the pinned CEF C++ API directly.

This is not a migration and does not change the default backend.

## 2. Current MCEF limitation

The previously measured stock MCEF screen showed a high Minecraft render rate
while browser rAF/paint stayed near 30 Hz. Its CEF 116 JCEF API does not expose
the native External BeginFrame creation setting or host call. A separate
patched JCEF/MCEF proof exists, but interactive GAME_SYNC acceptance remains
incomplete. Direct CEF makes the native controls measurable without that stack.

## 3. Architecture

```text
mcwebui_direct_cef_proof.exe
  -> CefExecuteProcess (same executable subprocess)
  -> CefInitialize (multi-threaded message loop)
  -> hidden Windows host + windowless CefBrowser
  -> real frontend/playground/dist/index.html over file://
  -> browser console aggregate (~1 message/second)
  -> CPU OnPaint / optional OnAcceleratedPaint
  -> aggregate JSON in an external result directory
```

The scheduler uses `steady_clock`/`sleep_until`; the request rate is measured
rather than assumed. Render callbacks only append timestamps and counters under
a short lock. They do not log, serialize JSON, or write to disk. The browser
probe adds one 2x2 transform-only element to the real Vue application. `--idle`
removes that mutation while retaining the rAF observer.

## 4. Fixed CEF baseline

- Distribution: Windows x64 standard binary SDK.
- CEF: `116.0.27+gd8c85ac+chromium-116.0.5845.190`.
- CEF commit: `d8c85aca1de77ab7e44ce121847380df7499daaa`.
- Chromium commit: `467d4024f439938f2cd936a9edb1e2a73405856e`.
- SDK archive SHA-256:
  `65FDB9117AE8578F2C3E208AB5EEE7A19A1E4F83EECB3D0CB712AB87E128D0A1`.
- Runtime `libcef.dll` SHA-256:
  `BF939EFEB24D668FAF844CBD3B5ADD5EFC22878094F045289FFD9D24832B94E8`.
- Build: VS Community 18.7.3 / MSVC 19.51, Windows SDK 10.0.26100,
  CMake 4.3.1, Ninja 1.13.2, Release x64.

The pinned headers directly confirm `external_begin_frame_enabled`,
`SendExternalBeginFrame`, `windowless_frame_rate`, `shared_texture_enabled`,
`OnPaint`, and `OnAcceleratedPaint`. CEF 5845 limits
`windowless_frame_rate` to 1..60, but External BeginFrame accepts independent
host opportunities.

## 5. CPU OSR result

**VERIFIED.** The executable built, CEF initialized, the real Vue bundle loaded,
and ongoing transform changes produced 1280x720 BGRA `OnPaint` callbacks.

Measured six-second runs on this development machine:

| Mode / target | Requests/s | Browser rAF/s | CPU OnPaint/s | Paint P95 |
| --- | ---: | ---: | ---: | ---: |
| Backend default | 0.00 | 30.00 | 30.01 | 46.94 ms |
| External 30 | 29.96 | 29.82 | 29.99 | 47.30 ms |
| External 60 | 60.07 | 60.73 | 55.34 | 31.13 ms |
| External 120 | 120.05 | 64.33 | 57.01 | 30.98 ms |
| External 144 | 143.91 | 64.47 | 57.29 | 30.91 ms |

The 1920x1080 External 60 run also loaded successfully: requests 60.04/s,
rAF 58.66/s, and CPU OnPaint 55.11/s. CPU/GPU utilization and process memory
were **NOT MEASURED** because no reliable profiler capture was established.

## 6. External BeginFrame result

**VERIFIED to improve the default path; VERIFIED approximately 60 Hz ceiling.**
External requests at 60 roughly doubled rAF and paint from the 30 Hz default.
At 120/144, request delivery remained accurate but browser rAF plateaued near
64 Hz and actual CPU paint near 57 Hz. This is classification **C** from the
acceptance matrix: requests 144, rAF ~60, paint ~60. It is not a 144 FPS result.

The scheduler interval distribution at 120/144 reflects Windows scheduling
bursts (including sub-millisecond catch-up intervals), but the separately
measured total request rate matched its target. Browser and paint ceilings are
therefore not inferred from the request counter.

## 7. Idle behavior

**VERIFIED for paint suppression.** With External 144 still issuing 143.92
opportunities/s and the visual mutation disabled, the browser rAF observer
reported 32.16/s while only the initial paint occurred (one callback; no
ongoing paint rate). A BeginFrame opportunity does not force a dirty texture.

## 8. Accelerated OSR result

CEF 5845 exposes the API and the proof sets `shared_texture_enabled` before
browser creation. Two 1280x720 External runs (60 and 120 target) still delivered
CPU `OnPaint` and zero `OnAcceleratedPaint`. Repeating with a real hidden parent
window produced the same result. The 60-target run measured requests 60.05/s,
rAF 59.47/s, CPU paint 55.79/s, accelerated callbacks 0.

Result: **FAILED on this runtime/environment**, not an API absence. No shared
handle was produced. The exact GPU/software fallback cause is not established;
upgrading CEF to hide this result is out of scope.

## 9. D3D11 shared texture result

The callback path is **IMPLEMENTED / NOT RUNTIME VERIFIED**. On a real
`OnAcceleratedPaint`, the proof creates a hardware D3D11 device, immediately
calls `OpenSharedResource`, reads the `ID3D11Texture2D` descriptor, and does not
retain the CEF handle beyond the callback. Since accelerated callbacks were
zero, D3D opening was not attempted and there is no descriptor to report.

## 10. Known limitations

- Windows amd64 only; no production packaging or helper executable split.
- CSS/direct rAF transform was exercised; Vue reactive stress, automated range
  dragging, and scroll input are **NOT TESTED** by this host.
- No CPU, GPU utilization, memory, power, or frame-present measurements.
- `file://` is proof-only and does not replace the production `mcui://` scheme.
- A one-second console aggregate is used instead of per-frame IPC.
- Accelerated OSR did not activate, so its handle lifetime and adapter matching
  remain runtime-unverified.

## 11. Thin JNI sketch

If a later CEF/runtime combination passes accelerated OSR, keep CEF objects in
C++ and expose only handles such as `createView`, `destroyView`, `resize`,
`setVisible`, `requestFrame`, input calls, and message exchange. Do not recreate
JCEF's object graph in Java. A dedicated `mcwebui-cef-helper.exe` may be safer
than assigning all subprocess roles to Minecraft's Java executable.

## 12. D3D/OpenGL next step

Do not implement WGL/DX interop yet. First establish why CEF 5845 returned CPU
paint with shared textures requested, or compare an isolated newer CEF proof.
Only after a valid shared handle is repeatedly opened should a separate proof
evaluate `WGL_NV_DX_interop2`, adapter identity, synchronization, texture pool
lifetime, and Minecraft render-thread ownership.

## 13. Packaging implications

A production Direct CEF backend would need per-platform native archives,
integrity metadata, process helper packaging, cache isolation, and deterministic
extraction/update behavior. None belongs in the Mod JAR in this proof. The root
Gradle build now provisions pinned Node/npm for frontend builds; that is a build
tool only and is not packaged into the mod.

## 14. Licensing

CEF, Chromium, and `libcef_dll_wrapper` notices/licenses must accompany any
future runtime distribution. No SDK, DLL, PAK, locale, wrapper build, cache, or
measurement output is committed in this repository.

## 15. Recommendation

**Verdict C: CPU path succeeds but Accelerated OSR is blocked; decide whether
CPU OSR performance is sufficient.** Direct CEF proves that bypassing
MCEF/JCEF can move CPU OSR from ~30 to ~55-57 paint callbacks/s, but it does not
prove 120/144 browser output or a usable GPU shared texture. Keep the current
MCEF backend and Direct CEF proof isolated. The next focused question is why
CEF 5845 ignored the requested shared-texture path on this machine; only then
decide between CPU Direct CEF, a newer isolated CEF proof, or an alternative
backend.
