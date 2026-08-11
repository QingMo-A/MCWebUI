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

## Bundled artifact audit

The checked local artifacts were:

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
* [CEF `cef_browser_settings_t.windowless_frame_rate`](https://cef-builds.spotifycdn.com/docs/145.0/structcef__browser__settings__t.html)
  (creation-time browser setting).
* [CEF browser header](https://github.com/chromiumembedded/cef/blob/master/include/cef_browser.h)
  (native `GetWindowlessFrameRate`/`SetWindowlessFrameRate` contract).
* [CinemaMod MCEF 2.1.6-1.21.1](https://github.com/CinemaMod/mcef/tree/2.1.6-1.21.1)
  (the maintained MCEF/JCEF distribution used by this target).
