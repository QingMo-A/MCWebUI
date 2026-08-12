# Direct CEF runtime proof

## Modern CEF 144 isolated profile (2026-08-12)

Status: **IMPLEMENTED / RUNTIME MEASURED; accelerated callback, D3D11 shared
texture, and GPU-only simulator present are VERIFIED on this host.** Visual
alpha inspection and real input forwarding remain **NOT TESTED**. The
production CEF/MCEF/JCEF configuration is unchanged.

The earlier `d992347` high-refresh measurements requested 120/144 while the
browser was configured with `windowless_frame_rate=60`; that historical rate
verdict is **SUPERSEDED / INCONCLUSIVE** for modern CEF above 60 Hz. The proof
now retains the CEF 5845 compatibility clamp while CEF 144 uses the requested
target, and every JSON result records `requestedTargetHz` plus
`configuredWindowlessFrameRate`. A proof-only `--windowless-frame-rate=N`
override supports controlled A/B runs without changing normal version-based
behavior.

The selected official automated-build metadata entry is the current stable
Windows x64 standard binary:

- CEF: `144.0.33+gcb4715c+chromium-144.0.7559.259`.
- Chromium: `144.0.7559.259`.
- CEF commit: `cb4715c47322f31bee2bf2ad9d9add3cf8fc8ea0`.
- Metadata: `https://cef-builds.spotifycdn.com/index.json` (stable,
  standard file; metadata observed 2026-08-12).
- Archive URL:
  `https://cef-builds.spotifycdn.com/cef_binary_144.0.33%2Bgcb4715c%2Bchromium-144.0.7559.259_windows64.tar.bz2`.
- Archive SHA-256:
  `CD03702954F21BDD773449D2DD37290BBBC4B908456837763A9DDB1FF42D6A40`.
- `Release/libcef.dll` SHA-256:
  `5A5556425AD319735175BB6CA386813B73AD515D49081BDE6FC0423F034D8EBE`.

The proof compiles against the modern SDK's actual header signature
(`OnAcceleratedPaint(..., const CefAcceleratedPaintInfo&)`) and embeds the
official CEF Windows compatibility manifest. Build result: **VERIFIED** with
VS Community 18.7.3/MSVC 19.51, CMake/Ninja, Release x64. The manifest was
material: before embedding it, the GPU process logged context creation errors
and exited with `-2147483645`; after embedding, the GPU process launched and
all CPU/accelerated runs loaded the real frontend bundle.

The 1280x720 modern matrix below used 2.5--3 seconds per run. Values are
measured from the result JSON; request rate is not presented as FPS.

| Mode | BeginFrame/s | Browser rAF/s | CPU OnPaint/s | Accelerated/s | D3D11 | Result |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| Windowed baseline | 0.00 | 180.08 (earlier run) | 0.00 | 0.00 | n/a | **NOT RUNTIME VERIFIED** (later run had rAF 0) |
| Backend default | 0.00 | 30.00 | 30.35 | 0.00 | n/a | **VERIFIED** |
| External 30 | 30.01 | 30.01 | 29.53 | 0.00 | n/a | **VERIFIED** |
| External 60 | 59.88 | 60.03 | 55.88 | 0.00 | n/a | **VERIFIED** |
| External 120 | 119.98 | 83.90 | 57.83 | 0.00 | n/a | **VERIFIED; ~60 paint ceiling** |
| External 144 | 144.22 | 86.58 | 55.88 | 0.00 | n/a | **VERIFIED; ~60 paint ceiling** |
| Accelerated 60 (legacy open attempt) | 59.89 | 59.74 | 0.00 | 45.56 | legacy `OpenSharedResource` `0x80070057` | **SUPERSEDED** |
| Simulator 60 (latest full matrix) | 59.94 | 60.35 | 0.00 | 54.85 | `OpenSharedResource1` success | **VERIFIED GPU present (133 frames)** |
| Idle external 144 | 144.70 | 36.62 | 0.00 | 0.00 | n/a | **VERIFIED paint suppression** |

Accelerated OSR is therefore not being silently classified as CPU success:
CEF delivered accelerated callbacks with no CPU `OnPaint`, and the callback
handle changed repeatedly. The first D3D11 implementation used the legacy
`OpenSharedResource` call and returned `E_INVALIDARG`; modern CEF's header
documents a no-keyed-mutex handle, so the proof now uses D3D11.1
`ID3D11Device1::OpenSharedResource1`. The latest full-matrix run presented 133
GPU frames at 54.85/s (an earlier standalone run presented 158 at 54.32/s).
The callback handle is reopened only inside its callback and never cached.

The optional `--simulator` path is **IMPLEMENTED / RUNTIME VERIFIED for GPU
submission**: a DXGI flip-discard swap chain renders a moving native
background and samples the CEF texture in a fullscreen pixel shader before
`Present`. No CPU readback is used. Visual alpha correctness, pixel capture,
mouse/keyboard forwarding, and interactive slider/scroll checks are **NOT
TESTED** in this headless run, so this is not yet a full transparent WebScreen
acceptance proof.

The JSON now includes `gpuDiagnostics`: child-process count, observed GPU and
renderer launches, the filtered GPU command-line switches, whether an
accelerated callback was observed, and whether D3D opening succeeded. The
modern run observed one GPU process and two renderer launches; the last GPU
command line was `--type=gpu-process` with no forced ANGLE/software switches.
This is direct CEF process evidence, not a Task Manager inference. Chromium
GPU utilization, ANGLE vendor/backend strings, GPU memory, and display-present
cadence remain **NOT MEASURED**.

The transparent D3D simulator's GPU submission/present is **VERIFIED**; alpha
pixel inspection, real mouse/keyboard input forwarding, and Minecraft
integration are **NOT TESTED**. No CPU readback fallback is used or claimed.

## 2026-08-12 decoupled mailbox checkpoint

The coupled simulator remains the historical baseline: it opens, composites,
and calls `Present(1)` from the accelerated paint callback. The mailbox path
separates those responsibilities. `OnAcceleratedPaint` opens the shared CEF
resource, copies it into one of three host-owned D3D11 textures, publishes a
generation, and returns. An independent consumer selects the latest complete
slot, composites it over the moving background, and presents at the requested
rate (`Present(0)` uncoupled or `Present(1)` VSync reference).

The producer and consumer serialize the D3D11 immediate context with a short
mutex and rely on command order for GPU copy/draw ordering. The producer uses
`try_lock`; a busy context drops immediately without sleeping, query/event
waits, flushing, presenting, or CPU readback. `Present` is outside the mutex,
and slot selection protects the current consumer slot, so the callback is
never coupled to VSync. This is a GPU-only three-slot mailbox.

Clean modern CEF 144 Release x64 measurements at 1280x720 (~8.4 s) were:

| Mode | Requests/s | rAF/s | Accelerated/s | Copy/publish/s | Consumer/present/s | New / repeat / no | Present median / P95 / max (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Mailbox uncoupled 60 | 60.00 | 59.71 | 54.44 | 54.20 | 59.95 / 59.95 | 357 / 129 / 7 | 15.723 / 26.616 / 31.146 |
| Mailbox uncoupled 120 | 120.07 | 78.42 | 57.89 | 57.78 | 119.98 / 119.99 | 446 / 530 / 10 | 8.692 / 16.992 / 23.029 |
| Mailbox uncoupled 144 | 143.99 | 86.58 | 57.14 | 56.15 | 143.95 / 143.97 | 440 / 729 / 13 | 5.448 / 16.151 / 21.874 |

`new + repeat + no` always equals `presents`; the lower `new` count at high
consumer rates is expected repeated presentation of the latest generation,
not a second producer ceiling. A 1920x1080 uncoupled-60 run recorded 228
accelerated/copy/published callbacks at 55.02/s and 59.88 presents/s. The
VSync-60 reference recorded 53.17 copies/published/s and 59.99 `Present(1)`
calls/s. Ten serial 350 ms modern lifecycles exited 0, loaded successfully,
preserved accounting, and left no proof processes running. The pinned CEF
5845 smoke still exits 0 with CPU OnPaint (~55.8/s) and zero accelerated
callbacks. Local frontend typecheck/build and all Forge/NeoForge tests/builds
pass; output JARs contain no CEF DLL/EXE/native runtime.

The mailbox descriptor observed is 1280x720, numeric DXGI format 87
(`DXGI_FORMAT_B8G8R8A8_UNORM`), one mip/array slice, sample count 1, default
usage, CPU access flags 0; CEF color type is 1 (`CEF_COLOR_TYPE_BGRA_8888`).
This is descriptor/opening evidence, not alpha pixel inspection. Visual alpha
and real mouse/keyboard/scroll input remain **NOT TESTED**.

Status: **VERDICT B** — modern CEF 144 configured to the requested target
exceeds 60 accelerated generations/s but remains well below 120/144 on this
host. Decoupling removes callback-owned VSync coupling; it does not manufacture
higher-rate CEF generations. Production Minecraft integration remains out of
scope until alpha and input gates pass.

### Modern CEF high-refresh verification (2026-08-12)

Clean 1280x720 accelerated mailbox runs used the same external BeginFrame,
three-slot GPU mailbox, `OpenSharedResource1`/`CopyResource`, uncoupled
`Present(0)`, and Vue probe. Rates are measured from callback or generation
timestamps, never inferred from requests.

| Target | Configured OSR Hz | BeginFrame/s | rAF/s | Accelerated/s | GPU copy/s | Published/s | Present/s | New/s | Repeat/s |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 60 | 60 | 60.03 | 59.83 | 55.06 | 54.61 | 54.61 | 59.93 | 49.68 | 10.28 |
| 120 | 120 | 120.09 | 64.59 | 64.38 | 64.27 | 64.27 | 119.96 | 63.70 | 56.40 |
| 144 | 144 | 144.10 | 64.69 | 64.42 | 64.09 | 64.09 | 143.98 | 63.43 | 80.58 |

The required A/B held target 144 and all other switches constant:

| Target | Configured OSR Hz | BeginFrame/s | rAF/s | Accelerated/s | GPU copy/s | Published/s | Present/s | New/s | Repeat/s |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 144 | 60 | 144.16 | 64.72 | 57.28 | 56.72 | 56.72 | 144.00 | 56.71 | 87.37 |
| 144 | 144 | 144.05 | 64.44 | 64.35 | 64.02 | 64.02 | 143.92 | 63.56 | 80.39 |

The 144/144 repeat measured 144.00 BeginFrame/s, 64.45 rAF/s, 64.55
accelerated/s, 64.44 copy/publish/s, and 144.01 Present/s. For the primary
144/144 run, accelerated interval median/P95/max was 15.484/16.659/31.334 ms
and published interval median/P95/max was 15.518/16.979/33.398 ms. The repeat
measured 15.502/16.719/29.891 ms and 15.533/16.731/29.872 ms respectively.
The 144/60 run measured accelerated 15.609/31.244/32.840 ms and published
15.649/31.396/48.032 ms. These are interval summaries, not display scanout.

For completeness, the 60/120/144 source result files were
`%TEMP%\\mcwebui-rate-smoke\\m60.json`, `m120.json`, and `m144.json`; the A/B
files were `%TEMP%\\mcwebui-rate-ab\\target144-config60.json` and
`target144-config144.json`. The repeat was
`target144-config144-repeat.json` in the same directory.

Conclusion: **Verdict B**. Removing the old 60 Hz configuration ceiling raises
CEF delivery from the high-50s into the low/mid-60s, but this environment still
has an approximately 64 Hz accelerated-generation ceiling. It does not prove
120 or 144 distinct browser generations.

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

The callback path is **VERIFIED** for modern CEF 144. On a real
`OnAcceleratedPaint`, the proof creates a hardware D3D11.1 device, immediately
calls `OpenSharedResource1`, reads the `ID3D11Texture2D` descriptor, and does
not retain the CEF handle beyond the callback. The sample reports 1280x720,
serialized `format: 1` is the CEF color enum
`CEF_COLOR_TYPE_BGRA_8888` (not a DXGI format; DXGI format 1 is not BGRA8).
The current JSON does not serialize the `ID3D11_TEXTURE2D_DESC::Format`
numeric value separately, so the exact DXGI format is **NOT MEASURED** in this
proof. CEF 5845 remains **NOT RUNTIME VERIFIED** for D3D opening because it
delivered zero accelerated callbacks.

## 10. Known limitations

- Windows amd64 only; no production packaging or helper executable split.
- CSS/direct rAF transform was exercised; Vue reactive stress, automated range
  dragging, and scroll input are **NOT TESTED** by this host.
- No CPU, GPU utilization, memory, power, or frame-present measurements.
- `file://` is proof-only and does not replace the production `mcui://` scheme.
- A one-second console aggregate is used instead of per-frame IPC.
- Adapter identity, alpha pixel correctness, input forwarding, and display
  scanout/present cadence remain **NOT MEASURED**. The simulator's
  `presentedFrames` counter measures successful DXGI `Present` calls, not
  physical display scanout.

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

**Verdict B: modern accelerated OSR plus a GPU-only mailbox compositor is
verified, and configured high-refresh CEF delivery reaches approximately 64/s
at 120/144 requests but not 120/144 distinct generations.** Keep the current MCEF backend and Direct CEF proof
isolated until visual alpha and real input are separately validated; do not
integrate into Minecraft yet.
