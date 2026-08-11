# GAME_SYNC / frame-pacing feasibility

Status: **VERIFIED IMPLEMENTATION PLAN — NO-GO for a native frame-rate change at this checkpoint**

Baseline: `e67388eb32fc54165f41ff7d454291f76a3220ea` on `bridge` (2026-08-11). The
working tree was audited with the pre-existing untracked root `web/` left untouched.

This plan records the A/B/C/D gates for the requested GAME_SYNC/frame-pacing work. It
does not claim an FPS number: the available runtime smoke test opens the browser and
verifies bridge/render startup, but is not an interactive benchmark.

## Decision

Do not add a frame-rate setter, a game-tick repaint loop, a retained CEF callback
buffer, reflection, a private JNI call, a binary patch, or a vendored native library.
The current MCEF surface already owns a persistent OpenGL texture and dirty-rectangle
updates. `NeoForgeMinecraftScreen.render` composites that texture when Minecraft draws;
`MCEFBrowser.onPaint` is scheduled by CEF independently. A second scheduler would not
make the browser produce frames faster and could race the native renderer or upload stale
pixels.

The safe result for this checkpoint is documentation plus the existing low-risk web
content hints (`overscroll-behavior` and `will-change: scroll-position`). Re-open the
implementation gate only after a supported MCEF/JCEF API exposes the CEF browser host or
browser-creation settings and a real benchmark measures paint cadence, game frame time,
CPU, and upload work.

The source-level route is understood (thread the CEF browser settings/host call through
java-cef, then rebuild and distribute matching JCEF/MCEF natives), but that is a new
cross-platform native release rather than a safe MCWebUI-only change. This checkpoint
cannot validate or ship that replacement, so the plan stops before any source fork or
binary patch.

## A/B/C/D gates

| gate | question | evidence | decision |
| --- | --- | --- | --- |
| A — CEF capability | Does CEF support a windowless rate request or an externally driven begin-frame path? | Official CEF documents `CefBrowserHost::SetWindowlessFrameRate(int)` and `CefBrowserSettings.windowless_frame_rate`; the documented range is 1–60 FPS with a default of 30. CEF also documents `SendExternalBeginFrame`, but it requires browser creation with `CefWindowInfo.external_begin_frame_enabled=true`. | **PASS for native CEF only.** These are native capabilities, not MCWebUI integration points by themselves. |
| B — supported Java/MCEF path | Does bundled JCEF/MCEF expose that capability? | `javap` against `mcef-2.1.6-1.21.1.jar` shows `MCEFBrowser` only exposes `resize`, input, paint, renderer and lifecycle methods; `MCEF` only creates browsers from URL/transparent/size; `MCEFSettings` has downloader/user-agent/cache options. `CefBrowser` has no `getHost`/frame-rate method, `CefSettings` has no browser `windowless_frame_rate`, and `CefBrowser_N` has no corresponding native method. | **FAIL.** There is no public, supported setter or creation setting to call. |
| C — GAME_SYNC safety | Can Minecraft's render/tick loop safely drive CEF paints? | `NeoForgeMinecraftScreen.render` draws MCEF's texture every game render. `InstrumentedMcefBrowser.onPaint` calls MCEF's implementation first; MCEF performs native texture/dirty-rect work. The bundled java-cef source at the audited `a78e832` revision has no `SendExternalBeginFrame`, `external_begin_frame_enabled`, `SetWindowlessFrameRate`, or `windowless_frame_rate` Java/JNI path; `CefBrowser_N` creation only constructs the native defaults. Retaining the callback `ByteBuffer` would violate its callback ownership and duplicate MCEF's cache. | **FAIL.** CEF has the native concepts, but this JCEF/MCEF Java + JNI + creation path does not expose them. Do not add a Java repaint loop or callback cache. |
| D — measurable acceptance | Can a 60-FPS or GAME_SYNC improvement be demonstrated here? | `FrameMetrics` records paint callback count and an explicitly estimated full-frame byte total, not game frame time, actual GPU upload bytes, or browser FPS. The existing runClient result is startup/bridge smoke only; no manual FPS data is fabricated. | **DEFERRED/NO-GO.** A future benchmark must capture paired before/after data on the same client profile. |

## Selected result: RESULT C — source-level JCEF JNI patch required

The missing integration is small enough to describe precisely, but it cannot be shipped as
an MCWebUI-only Java change. The external-begin-frame flag is immutable browser-creation
state, and the later frame signal crosses JNI. A reliable implementation therefore requires
a matched Java/JNI/native release and real platform builds. This checkpoint stops at a
verified implementation plan because those artifacts were not built and runtime-tested.

The minimum source patch surface is:

1. CinemaMod java-cef `java/org/cef/browser/CefBrowser.java`: expose an external begin-frame
   operation (and, if the fixed fallback is retained, get/set windowless frame-rate methods).
2. `java/org/cef/browser/CefBrowser_N.java`: forward the public operation to a new native
   declaration and carry an `externalBeginFrameEnabled` creation option. The option must be
   known before `N_CreateBrowser`; enabling it after creation is not supported by CEF.
3. `java/org/cef/browser/CefBrowserOsr.java` plus the browser factory/client creation path:
   preserve the option from OSR construction through `createBrowser` without changing the
   default for unrelated MCEF users.
4. Generated `native/CefBrowser_N.h` and `native/CefBrowser_N.cpp`: set
   `windowInfo.external_begin_frame_enabled` before `CefBrowserHost::CreateBrowser`, and add
   the JNI method that obtains the browser host and calls `SendExternalBeginFrame()` on the
   supported thread. A fixed fallback would separately bind
   `GetWindowlessFrameRate`/`SetWindowlessFrameRate`; it remains capped by CEF at 60 and is
   approximate synchronization, not GAME_SYNC.
5. CinemaMod MCEF `MCEFBrowser`/`MCEF.createBrowser`: add an opt-in creation overload or
   settings object and a public frame-signal method. Existing overloads must keep backend
   defaults so other mods do not create externally paced browsers that receive no signals.
6. MCWebUI can then add a backend-neutral optional capability. The active Screen's real
   `render` call may signal it at most once per visible render frame; close/hidden surfaces
   stop signaling. Unsupported backends degrade to `BACKEND_DEFAULT` without failing startup.

Build and distribution are the dominant maintenance cost. CinemaMod's MCEF downloader binds
Java classes to native archives by the java-cef commit and mirror. A patched release must
build and test matching JCEF archives for all six declared targets (`linux_amd64`,
`linux_arm64`, `windows_amd64`, `windows_arm64`, `macos_amd64`, `macos_arm64`), publish each
archive and checksum from a controlled mirror, and ship MCEF metadata/API that selects the
same revision. Mixing the patched Java classes with the current `jcef.dll` is invalid. A
Windows-only proof would not justify publishing GAME_SYNC as a portable MCWebUI capability.

Licensing also travels with that distribution: CEF/java-cef use the BSD-style CEF license and
require retained copyright/license notices for source and binary redistribution; MCEF is
LGPL-2.1-or-later and a modified distributed build requires the applicable notices and
corresponding modified library source. No patched artifact is produced by this checkpoint.

## Bundled artifact audit

The checked local artifacts were:

* `com.cinemamod:mcef-neoforge:2.1.6-1.21.1`, SHA-256
  `5ED9889A65AC2673B1FD0BF92EC6B39EE933A1FCBA23202AD5E065E1B012C804`.
* `com.cinemamod:mcef:2.1.6-1.21.1`, SHA-256
  `C6EB3842D1F5EE80A5133EA22EE380706E0B1BB2E7028B841C074A92CA33FF96`.
* `build/mcef-libraries/windows_amd64/jcef.dll`, SHA-256
  `FF7E27A7EB27532D2FCBA87E1278B7432ED696164A5222E5D0FB0C93DD8245B8`.
* `build/mcef-libraries/windows_amd64/libcef.dll`, SHA-256
  `BF939EFEB24D668FAF844CBD3B5ADD5EFC22878094F045289FFD9D24832B94E8`.

The local `libcef.dll` contains strings for the native CEF method and generic Chromium
`disable-frame-rate-limit`, but a binary string is not proof of a Java-callable export;
neither is a supported MCEF setting. The latter is a compositor command-line switch
rather than the CEF OSR maximum and must not be used as a substitute for
`SetWindowlessFrameRate`.

The exact public signatures were checked with `javap -public`/`javap -private`. In
particular, the bundled `CefBrowser_N` native list contains browser creation, resize,
invalidate, input, and move/resize notifications but no frame-rate or external-begin-frame
native. A source audit of CinemaMod's java-cef `a78e832` revision likewise found no
`external_begin_frame`, `SendExternalBeginFrame`, `SetWindowlessFrameRate`, or
`windowless_frame_rate` path; its native browser creation constructs default
`CefWindowInfo`/`CefBrowserSettings` and does not expose those options to Java. The local
MCEF README identifies the native Chromium line as 116.0.5845.190; no private API is
assumed from another JCEF revision.

## Current pipeline and limits

```text
CEF OSR scheduler
  -> MCEFBrowser.onPaint (native callback)
  -> MCEFRenderer persistent texture + dirty rectangles
  -> NeoForgeMinecraftScreen.render (Minecraft game render)
  -> GUI quad
```

The two clocks are intentionally independent. Minecraft can redraw the last texture
more frequently than CEF paints, but that does not increase browser animation cadence.
Conversely, CEF can paint while Minecraft is between render calls; MCEF owns the texture
update and the next game render observes it. The adapter must not synchronize these
threads by holding the callback buffer or issuing undocumented JNI calls.

## Verified implementation plan for a future supported API

This is a gated plan, not work performed in this checkpoint:

1. Upgrade/select an MCEF/JCEF release whose public Java API exposes either
   `CefBrowser.getHost().setWindowlessFrameRate(60)` or a documented creation-time
   `CefBrowserSettings.windowless_frame_rate` field. Record the exact jar/native hashes.
2. Add a backend-neutral optional capability (not a common hard dependency on CEF) and a
   NeoForge target setting. Keep the default unchanged until a real benchmark validates
   the request.
3. Apply the request after browser creation on the supported browser/UI thread, and
   preserve MCEF's texture ownership and dirty-rect path. Do not add `onPaint` buffer
   retention or a Minecraft tick-driven `invalidate` loop.
4. Extend diagnostics with requested/effective rate only when the API can report it;
   continue to distinguish paint callbacks from game frames and GPU uploads.
5. Run a paired static/scroll/animated benchmark with and without the request, recording
   paint cadence, Minecraft frame time/FPS, CPU, memory, and upload telemetry. A real
   `runClient` observation is required before changing the default.

## Accepted low-risk work

The playground avoids an idle diagnostics timer and keeps the nested signal list's
scroll chaining local with `overscroll-behavior: contain`; `will-change:
scroll-position` is only a compositor hint. These hints do not claim a fixed FPS and
should be retained only if profiling shows they help the target content. No additional
CSS containment, forced layer promotion, animation throttling, or native switch is
introduced without measurement.

## Primary sources

* [CEF `CefBrowserHost::SetWindowlessFrameRate`](https://cef-builds.spotifycdn.com/docs/115.2/classCefBrowserHost.html)
  (native OSR limit, default 30, maximum 60, and creation-setting reference).
* [CEF 5845 browser header](https://github.com/chromiumembedded/cef/blob/5845/include/cef_browser.h)
  (the Chromium 116 line used here; native `SendExternalBeginFrame` and
  `GetWindowlessFrameRate`/`SetWindowlessFrameRate` contracts).
* [CEF 5845 browser settings](https://github.com/chromiumembedded/cef/blob/5845/include/internal/cef_types.h)
  (creation-time `windowless_frame_rate`, default 30 and maximum 60).
* [JCEF browser implementation](https://github.com/chromiumembedded/java-cef/tree/master/java/org/cef/browser)
  (current upstream comparison only; it is not substituted for the bundled revision).
* [CinemaMod java-cef `a78e832`](https://github.com/CinemaMod/java-cef/tree/a78e832f9f13c2c688caea3d04d8b84fcd238d94)
  (the exact Java/JNI source revision bundled by MCEF and audited above).
* [CinemaMod MCEF 2.1.6-1.21.1](https://github.com/CinemaMod/mcef/tree/2.1.6-1.21.1)
  (the maintained MCEF/JCEF distribution used by this target).
