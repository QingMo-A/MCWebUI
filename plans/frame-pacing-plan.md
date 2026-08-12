# Windows amd64 GAME_SYNC native proof

Status: **PROOF B — standalone patched MCEF NeoForge artifact built; client/runtime FPS proof remains incomplete**

Execution baseline: `7416e2f9bae7db4c6dc5102daf4aa06c4b24fb17` on `bridge`.
The pre-existing untracked root `web/` was left untouched.

## Result

The native route is real and narrowly scoped:

```text
Minecraft Screen.render
  -> NeoForgeWebSession.beginFrame
  -> optional common BrowserFramePacing
  -> proof-only MCEFBrowser
  -> patched JCEF Java/JNI
  -> CefBrowserHost::SendExternalBeginFrame
```

`CefWindowInfo.external_begin_frame_enabled` is set before browser creation. Calls from
Minecraft's render thread are posted non-blockingly to CEF's UI thread. Stock source and
dependencies remain callback-driven; proof mode is opt-in and rejects missing or mismatched
JAR/native hashes.

The historical MCEF root build still combines Fabric Loom `1.7-SNAPSHOT` with Gradle 8.8 and
is not used. Instead, `scripts/frame-proof/build-mcef-standalone.ps1` generates a temporary
NeoForge ModDevGradle 2.0.141 project which compiles the exact external MCEF/JCEF source
checkouts and applies only the committed GAME_SYNC patches. The source revisions and runtime
Java behavior are unchanged. This produced a patched NeoForge jar (PROOF B), and a proof
`runClient` reached Minecraft with patched MCEF/JCEF and CEF initialized. F8 interaction,
rAF/paint >30, 60/120/unlimited comparisons, idle paint behavior, and Minecraft performance
impact are still **NOT VERIFIED**.

## Exact baseline and artifacts

- Minecraft `1.21.1`; NeoForge `21.1.216`.
- CinemaMod MCEF `2.1.6-1.21.1`, source commit
  `c89e242092b11be9a10ee9ffebecc7f9f5b55c0a`.
- CinemaMod java-cef
  `a78e832f9f13c2c688caea3d04d8b84fcd238d94`.
- CEF `116.0.27+gd8c85ac+chromium-116.0.5845.190` binary SDK, SHA-256
  `65FDB9117AE8578F2C3E208AB5EEE7A19A1E4F83EECB3D0CB712AB87E128D0A1`.
- Patched JCEF Java proof JAR, SHA-256 `A2536340224814F74820C4C39E060B8B0E6E067C35AAC849B43E8F40327D7BD0`.
- Patched Windows amd64 `jcef.dll`, SHA-256
  `E893C5A9AEA5C4230820DB564FD92C37D757B012EB359F14E5F647464F3FF1CC`.
- Reused stock `libcef.dll`, SHA-256
  `BF939EFEB24D668FAF844CBD3B5ADD5EFC22878094F045289FFD9D24832B94E8`.
- Standalone patched MCEF NeoForge proof JAR, source `c89e242`, SHA-256
  `1519223E9780BEF8AB3FAD45D5FAC625656CB59E0C63BAFFCA373CD02A2C1677`, 207 entries.

No Chromium/CEF source build occurred. Only `libcef_dll_wrapper` and JCEF JNI were compiled.
The toolchain was Visual Studio Community 18.7.3, MSVC 19.51.36248, Windows SDK
10.0.26100, bundled CMake 4.3.1/Ninja 1.13.2, JDK 21.0.10, Python 3.10, Release x64.
`dumpbin` confirms both JNI exports:
`N_SendExternalBeginFrame` and `N_SupportsExternalBeginFrame`.

## Source of truth

- `native/patches/jcef-a78e832-game-sync.patch`
- `native/patches/mcef-2.1.6-game-sync.patch`
- `scripts/frame-proof/prepare-cef-sdk.ps1`
- `scripts/frame-proof/build-jcef-proof.cmd`
- `scripts/frame-proof/build-mcef-proof.ps1` (historical root-build diagnostic)
- `scripts/frame-proof/build-mcef-standalone.ps1` (reproducible proof builder)
- `native/README.md`

Both source patches apply cleanly to fresh exact checkouts. No source checkout, SDK,
native library, archive, or patched third-party JAR is committed.

## Safety and lifecycle

Common contains only a thin optional capability; it contains no MCEF/JCEF/CEF type. A
visible active session sends at most one opportunity per host `render` call. Hidden,
closing, closed, and disposed sessions stop signaling. The call never waits for `OnPaint`.
MCEF's existing persistent OpenGL texture and dirty-rectangle paint path remain authoritative;
MCWebUI does not retain callback buffers or upload a second framebuffer.

The patched Java capability also calls a native marker. A patched Java/stock native mixture
therefore fails instead of advertising GAME_SYNC. The MCEF patch accepts an explicit proof
native directory and bypasses its downloader for that run, preventing it from replacing the
patched DLL. Gradle additionally requires caller-supplied SHA-256 values for the patched MCEF
JAR and `jcef.dll`.

## Instrumentation

`FrameMetrics` reports separate cumulative counters and approximately one-second completed
windows for game render signals, external BeginFrame requests, and CEF paint callbacks.
The showcase adds a default-off rAF visual probe with explicit Start/Stop and unmount cleanup.
Diagnostics distinguish `GAME_SYNC`/`BACKEND_DEFAULT`, capability, `PATCHED`/`STOCK`, and the
three host/browser rates. These counters are measurement plumbing, not fabricated FPS proof.

## Proof-mode invocation

After producing a matched patched MCEF NeoForge JAR and native directory:

```powershell
$env:MCWEBUI_PATCHED_MCEF_JAR = '<patched mcef-neoforge jar>'
$env:MCWEBUI_PATCHED_NATIVE_DIR = '<parent directory containing windows_amd64/jcef.dll siblings>'
$env:MCWEBUI_PATCHED_MCEF_SHA256 = '<jar sha256>'
$env:MCWEBUI_PATCHED_JCEF_SHA256 = '<jcef.dll sha256>'
.\gradlew.bat :targets:neoforge-1.21.1:runClient -PmcwebuiFramePacingProof=true --no-daemon
```

The standalone artifact is intentionally external and is not copied into the repository.
Without all four values, proof configuration fails intentionally. Normal builds do not
require local artifacts and continue using the official CinemaMod coordinates.

## Acceptance still required

For PROOF A, repeat the same scene/window/GUI scale at 60, 120, and unlimited/high FPS.
Capture game signals/s, external BeginFrames/s, browser rAF/s, CEF paints/s, Minecraft
frame time/FPS, CPU, and memory. Success requires both rAF and paint >30; 144 external
signals with paint fixed at 30 or 60 is not success. Also record stopped-animation idle
paint behavior and close/reopen lifecycle. Until then GAME_SYNC remains experimental and
off by default.

## Portability and licenses

This is Windows amd64 proof work, not portable support. Remaining native builds are
Windows arm64, Linux amd64/arm64, and macOS amd64/arm64. Scripts parameterize source,
SDK, build, JDK, CMake, and toolchain locations for a future CI matrix.

CEF/java-cef redistribution must retain BSD-style notices. Modified MCEF distribution is
LGPL-2.1-or-later and must preserve notices and provide corresponding modified library
source. See `native/README.md`; no third-party binary is distributed here.

## Primary references

- [CEF 5845 browser API](https://github.com/chromiumembedded/cef/blob/5845/include/cef_browser.h)
- [CEF 5845 creation types](https://github.com/chromiumembedded/cef/blob/5845/include/internal/cef_types.h)
- [CinemaMod java-cef a78e832](https://github.com/CinemaMod/java-cef/tree/a78e832f9f13c2c688caea3d04d8b84fcd238d94)
- [CinemaMod MCEF 2.1.6-1.21.1](https://github.com/CinemaMod/mcef/tree/2.1.6-1.21.1)
