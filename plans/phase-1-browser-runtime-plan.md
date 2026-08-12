# Phase 1 — Browser Runtime Vertical Slice

Status: **IMPLEMENTED / CORE RUNTIME ACCEPTED (NeoForge-only scope; extended input pass pending)**

Depends on: `plans/architecture-plan.md`

## 1. Objective

Prove the entire MCWebUI concept with one small end-to-end path before building a large SDK or component library.

Phase 1 is complete when a packaged Vue application can:

1. render inside a normal Minecraft `Screen`;
2. receive mouse, wheel, keyboard, focus, clipboard, and usable Chinese text input;
3. invoke a structured Java RPC and receive a structured result;
4. receive a Java-pushed state update reactively in Vue;
5. load HTML/JS/CSS from the mod/JAR without an external web server;
6. operate on both Forge 1.20.1 and NeoForge 1.21.1 through the same common contracts and frontend bundle;
7. shut down/reopen without leaking browser views or textures.

Visual polish is secondary to proving this path cleanly.

### Scope checkpoint (2026-08-11)

The user-directed checkpoint narrows new implementation and runtime acceptance to **NeoForge 1.21.1**. The Forge 1.20.1 module and manifest entry remain untouched as preserved project structure; no Forge runtime result is claimed here. CinemaMod MCEF `2.1.6-1.21.1` is integrated as the real NeoForge browser backend, including an official JCEF `CefMessageRouter`/`CefQuery` transport and trusted-origin bootstrap. `runClient` now verifies the core F8 display and bridge path; extended input/lifecycle acceptance remains a manual follow-up.

Implementation checkpoint commits: `5511d16` (`implement phase one browser runtime slice`) and `0f9f83a` (`integrate neoforge mcef backend`). Documentation checkpoints are recorded in git history.

### Runtime acceptance checkpoint (2026-08-11)

Implementation commits: `c494056` (`fix bridge bootstrap lifecycle and runtime acceptance`) and `9b28e6a` (`preserve disconnected state on bridge close`). The previous frontend race eagerly captured a missing `window.__MCWEBUI_BRIDGE__`; the default transport now resolves the current host at connect/send time, listens for a private bridge-ready event, and uses sparse bounded fallback checks. A client can therefore be created before CEF installs the host and still complete handshake, RPC, and state delivery later. Local state listeners are retained across connection failures; the core client activates one host subscription per channel only after handshake, removes it after the last local listener, and re-subscribes on reconnect. Closing while waiting for a host cancels the connect operation, clears listeners/timers/waiters, leaves the client disconnected, and ignores later host installation. Vitest locks this exact delayed-host race plus RPC, state, unsubscribe, close, reconnect, and transport-error behavior.

`DefaultWebView.initialize()` now only reaches browser `READY`; it does not call `WebBridge.handshake()`. Browser handshake is negotiated by the JS transport. Host-side `publishState()` remains valid before a browser connects, while browser RPC/state subscribe operations require negotiated capabilities. Trusted reload and untrusted navigation clear bridge globals, host subscriptions, queued messages, and handshake state; close removes the `SURFACES` entry and closes bridge/browser resources.

The mapped NeoForge 21.1.216 `Screen.java` source was inspected from `neoforge-21.1.216-sources.jar`: `Screen.resize(Minecraft,int,int)` updates dimensions and calls `repositionElements()` without invoking `init()`. `NeoForgeWebSession` nevertheless guards `init()` with deterministic once-only initialization and cleans partial creation, preventing duplicate WebViews/MCEF browsers if a future lifecycle path invokes init again. `BrowserBackend` exposes only `createSurface(config, bridge)`. Diagnostics now distinguish Minecraft GUI dimensions, browser viewport dimensions, optional measured framebuffer dimensions, GUI scale, paint callbacks, and `estimatedPaintBytes`; the estimate is explicitly not GPU-upload telemetry.

Automatic frontend and Java validation for this checkpoint passed: `npm ci`, `npm run typecheck`, `npm run build`, `npm run test` (8 tests), `:common:test`, NeoForge tests, `architectureCheck`, `testAllTargets`, `compileAllTargets`, and `buildAllTargets`. Implementation commit `c725903` fixes dev-run resource staging, exact CEF response lengths, early custom-scheme registration, qualified trusted origins, GUI quad rendering, and minimal browser handshake decoding. A real `runClient` pass opened the F8 screen, rendered the shared bundle, reached `Connected`, received Java-pushed `demo.counter`, returned `demo.ping`, and populated NeoForge/Minecraft/MCEF diagnostics. Mouse/wheel/keyboard editing, clipboard, resize, GUI scale, close/reopen, and Chinese IME remain **NOT FULLY VERIFIED** (no synthetic IME claim). The common source-set merge debt remains documented and unchanged; Forge implementation remains out of scope.

### Paint-cache and viewport checkpoint (2026-08-11)

The target already inherits MCEF's persistent OpenGL texture and dirty-rectangle upload behavior, so MCWebUI does not retain the callback-owned CEF `ByteBuffer` or add a second full-frame cache. The remaining avoidable work was an oversized viewport and a self-sustaining diagnostics repaint loop. `NeoForgeWebSession` keeps GUI-sized dimensions when `followGuiSize=true`, can use framebuffer-equivalent dimensions when it is false, renders the browser opaque without alpha blending, and playground diagnostics refresh only after connection, when entering Runtime, or on explicit request. The nested signal feed contains overscroll and supplies a `will-change: scroll-position` compositor hint without introducing aggressive visibility/layout containment. With `guiScale=2`, GUI mode reduces browser pixels relative to framebuffer mode while the latter preserves physical CSS density across GUI scales.

NeoForge now registers `config/mcwebui-client.toml` with `followGuiSize=true` by default. `false` selects a framebuffer-equivalent viewport (`round(guiSize * guiScale)` per axis), updates it when the physical window or GUI scale changes, keeps the Screen quad fitted to the current GUI, and maps X/Y independently from current GUI coordinates into the browser coordinates. Non-square resize mapping and GUI-scale-equivalent dimensions are unit-tested. Diagnostics report `followGuiSize` and `viewportMode` (`GUI` or `FRAMEBUFFER`); the shared showcase displays the mode without polling while idle. CEF documents a default 30 FPS maximum for windowless paint callbacks, but the MCEF/JCEF 2.1.6 Java surface used here exposes no public frame-rate setter; no reflective/native workaround is claimed.

The playground no longer uses a native HTML `select` for the choice sample. CEF OSR could leave that platform popup visible after selection, so the sample now uses an in-page Vue listbox with selection, outside-pointer close, focus restoration, and ARIA behavior. This keeps popup pixels inside the normal browser paint lifecycle.

### GAME_SYNC / frame-pacing native checkpoint (2026-08-12)

The requested native frame-pacing path is now **PROOF C / source-patched with the Windows JCEF wrapper built, runtime incomplete** at
this dependency line. CEF itself documents `CefBrowserHost::SetWindowlessFrameRate` and
`SendExternalBeginFrame`; stock CinemaMod MCEF/JCEF `2.1.6-1.21.1` exposes neither through
its Java/JNI classes or browser-creation settings. The NeoForge Screen therefore keeps
compositing MCEF's persistent texture each Minecraft render while CEF owns `onPaint`
scheduling in stock mode. The proof source adds only the explicit JNI route; it does not
use Java tick-driven invalidation, callback-buffer retention, reflection, a binary edit,
or a generic Chromium switch. The
bundled `FrameMetrics` counters do not constitute an FPS benchmark. Gate evidence,
artifact hashes, and the future supported-wrapper implementation steps are in
[`plans/frame-pacing-plan.md`](frame-pacing-plan.md). Exact JCEF/MCEF source patches and parameterized build scripts are now present. A matching MCEF NeoForge JAR is blocked by the pinned upstream Loom/Gradle compatibility issue, so no GAME_SYNC `runClient`, rAF/paint >30 result, or performance claim is made.

## 2. Scope freeze

### Required

- common browser/runtime interfaces;
- first MCEF/JCEF-backed browser implementation behind target/backend adapters;
- browser surface to Minecraft texture path;
- one MCWebUI screen per target;
- local bundled resource loading;
- bridge handshake;
- request/response RPC;
- Java → browser state publication;
- TypeScript core bridge client;
- Vue composable for state/RPC;
- minimal Vue/Vite playground;
- input routing;
- resize and GUI-scale handling;
- security origin/capability gate;
- instrumentation sufficient to measure frame/upload behavior.

### Explicitly deferred

- browser pool sophistication beyond what is needed to avoid obvious leaks;
- dirty-region optimization if the selected backend requires a full-frame first implementation;
- React/Svelte bindings;
- Minecraft-native ItemStack overlays;
- HUD/world-space surfaces;
- arbitrary remote web pages;
- automatic TypeScript generation from Java DTOs;
- migrating production EconomySystem pages.

## 3. Dependency decision gate

Before implementation, Codex must verify current compatible releases/documentation for:

- MCEF implementation/fork chosen for Forge 1.20.1;
- MCEF implementation/fork chosen for NeoForge 1.21.1;
- whether one backend line can serve both targets;
- required Maven repositories/artifacts;
- JCEF/CEF native distribution behavior;
- current Vue 3, Vite, TypeScript versions.

Do not copy dependency coordinates from old examples without verification.

Record the selected versions and source references in this plan when implementation begins.

The public MCWebUI API must remain backend-neutral even if both targets use the same MCEF implementation.

### Verified dependency selections

| component | selected version / candidate | source | reason and target compatibility |
| --- | --- | --- | --- |
| Gradle wrapper | 8.13 | [Gradle distribution](https://services.gradle.org/distributions/) | Required by the verified NeoForge ModDevGradle 2.0.141 line and compatible with the local JDK 17/21 toolchains. |
| ForgeGradle | `[6.0,6.2)` (research only) | [ForgeGradle 6.x docs](https://docs.minecraftforge.net/en/fg-6.x/) | Official range for Forge 1.20.1; Forge implementation is out of this scope checkpoint and is not applied. |
| NeoForge build plugin | ModDevGradle `2.0.141` | [NeoForged ModDevGradle](https://github.com/neoforged/ModDevGradle) | Official ModDev plugin used by the real NeoForge compile/run path; requires Gradle 8.13 in this environment. |
| NeoForge userdev | `net.neoforged:neoforge:21.1.216` | [NeoForge Maven](https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.216/) | Minecraft 1.21.1 userdev coordinates used by the target's ModDev configuration. |
| MCEF backend | CinemaMod `com.cinemamod:mcef:2.1.6-1.21.1` (compile-only) + `com.cinemamod:mcef-neoforge:2.1.6-1.21.1` (runtime) | [CinemaMod/mcef 2.1.6-1.21.1](https://github.com/CinemaMod/mcef/tree/2.1.6-1.21.1), [CinemaMod release metadata](https://mcef-download.cinemamod.com/repositories/releases/com/cinemamod/mcef/maven-metadata.xml) | Official tag, POMs, jars, MCEF APIs, and NeoForge metadata were verified. The target uses `MCEF.createBrowser`, `MCEFBrowser`, `MCEFRenderer`, and `CefApp.registerSchemeHandlerFactory` without leaking these types into common. |
| Forge MCEF reference | MCEF `2.1.6-1.20.1` (artifact listing only) | [CurseForge files](https://www.curseforge.com/minecraft/mc-mods/mcef/files/all?page=1&pageSize=20&version=1.20.1) | Confirms a historical Forge 1.20.1 build, but does not establish a shared backend with NeoForge 1.21.1 and is outside this checkpoint. |
| Vue | `3.5.41` | [npm Vue registry](https://registry.npmjs.org/vue/latest) | Current stable Vue 3 release used by the shared playground. |
| Vite | `8.2.1` | [npm Vite registry](https://registry.npmjs.org/vite/latest) and [Vite compatibility docs](https://vite.dev/guide/) | Current stable release; requires Node `^20.19.0 || >=22.12.0`. |
| TypeScript | `7.0.2` | [npm TypeScript registry](https://registry.npmjs.org/typescript/latest) | Current stable compiler used for core/vue/playground typechecks. |
| Node.js | `22.22.2` for local validation; requirement `>=20.19.0 || >=22.12.0` | [Node.js release archive](https://nodejs.org/en/download/archive/v22) | Satisfies Vite's documented engine requirement. |

MCEF/JCEF native binaries are downloaded by CinemaMod MCEF's own bootstrap/downloader; this repository does not copy native binaries. The development client smoke test reached `Chromium Embedded Framework initialized`; no native library is vendored in the MCWebUI JAR.

## 4. Vertical-slice architecture

Target runtime flow:

```text
Minecraft Screen
      ↓
target WebScreen adapter
      ↓
common WebView session/lifecycle
      ↓
BrowserBackend port
      ↓
MCEF/JCEF adapter
      ↓
off-screen browser pixels
      ↓
target texture uploader
      ↓
Minecraft GUI
```

Bridge flow:

```text
Vue
 ↓
@mcwebui/vue
 ↓
@mcwebui/core
 ↓
JSON/binary-neutral bridge envelope
 ↓
common Java bridge
 ↓
typed handler
```

State flow:

```text
Java state store
 ↓
bridge state message
 ↓
@mcwebui/core subscription
 ↓
Vue ref/computed
```

## 5. Common Java API slice

Phase 1 should create only the interfaces required by the vertical slice.

Suggested conceptual API:

```text
WebRuntime
WebView
WebViewConfig
WebViewListener
BrowserBackend
BrowserSurface
FramePolicy
WebResourceResolver
WebBridge
WebRpcRegistry
WebStateStore
WebPermissionPolicy
```

Prefer small capability-based ports over one giant platform interface.

Do not put `Screen`, `GuiGraphics`, `Minecraft`, `ResourceLocation`, Forge, NeoForge, MCEF, JCEF, or CEF implementation types in common signatures.

## 6. View lifecycle

At minimum define deterministic states comparable to:

```text
CREATED
INITIALIZING
READY
VISIBLE
HIDDEN
CLOSING
CLOSED
FAILED
```

Required invariants:

- create/destroy are idempotent or fail predictably;
- closing a Minecraft screen cannot leave a texture registered forever;
- browser callbacks after close cannot mutate a destroyed view;
- resize while initializing is handled safely;
- focus is explicitly transferred to/from the browser;
- backend failure produces a visible/debuggable error instead of a black screen with no diagnostics.

## 7. Resource resolver

Implement a controlled local origin/scheme for packaged content.

Target semantics:

```text
mcui://playground.mcwebui/index.html
mcui://playground.mcwebui/assets/...
```

Requirements:

- namespace registration;
- path traversal rejection (`..`, encoded traversal variants, invalid separators);
- deterministic MIME types;
- cache policy suitable for development vs packaged mode;
- no arbitrary host filesystem access;
- no need for a local HTTP server in packaged play.

If CEF scheme registration imposes startup-order constraints, hide those constraints inside the backend/bootstrap layer.

## 8. Bridge protocol v0

Start with JSON unless profiling proves serialization cost matters.

Envelope should carry at least:

```text
protocol version
message kind
message/request id
method/channel
payload
error code/message when applicable
```

Message kinds required in Phase 1:

- handshake;
- request;
- response;
- event;
- state update.

Required behavior:

- request IDs are unique within a live view;
- Promise resolves/rejects exactly once;
- malformed/unknown messages fail safely;
- bridge calls before READY fail predictably or queue under an explicit policy;
- pending RPCs reject when the view closes;
- Java handler exceptions are converted to structured bridge errors without leaking arbitrary stack traces to untrusted pages.

## 9. Demo RPC

The first RPC should prove typed request and response without involving real economy data.

Example semantic method:

```text
mcwebui.demo.echo
```

Request:

```json
{
  "text": "hello"
}
```

Response:

```json
{
  "text": "hello",
  "target": "neoforge-1.21.1"
}
```

A second action may request basic safe client facts such as GUI scale or MCWebUI target capabilities.

Do not expose generic reflection or command execution for convenience.

## 10. State demo

Common Java should expose a state channel such as:

```text
demo.counter
```

The playground displays it through a Vue composable.

Java changes the value; Vue updates without a manual page reload or polling loop.

The test should prove unsubscribe/close cleanup.

## 11. Frontend Phase 1

Add verified current versions of:

- Vue 3;
- Vite;
- TypeScript.

Turn `frontend/playground` into a real Vite application.

Expected package responsibilities:

### `@mcwebui/core`

- bridge transport abstraction;
- handshake;
- `invoke()`;
- event listener lifecycle;
- state subscription;
- structured errors;
- capability query.

### `@mcwebui/vue`

- injected MCWebUI client;
- `useMcState()`;
- RPC helper/composable;
- cleanup on component unmount.

### playground

One clean page showing:

- runtime/target identity;
- connection state;
- Java-pushed counter;
- text input;
- RPC button/result;
- resize/debug metrics panel.

Do not spend Phase 1 building a full visual design system.

Checkpoint result: `@mcwebui/core` implements `connect`, `invoke`, `on`, late-bound host resolution, explicit first-listener/last-listener state subscribe/unsubscribe after handshake, request IDs, pending Promise correlation, structured errors, event dispatch, and a private `window.__MCWEBUI_BRIDGE__` transport adapter. `@mcwebui/vue` provides reactive `useMcConnection`, `useMcBridge`, `useMcState`, and `useMcRpc` without reproducing host transport lifecycle. The playground is a responsive component/runtime showcase and remains the only frontend bundle source of truth. Target identity and diagnostics are fetched from `runtime.diagnostics`; no loader/version is hardcoded in `frontend/**`.

## 12. Build integration

The frontend build must become part of the project build path once the playground is functional.

Desired production path:

```text
npm frontend build
      ↓
frontend dist
      ↓
common or target resources staging
      ↓
Forge/NeoForge JAR
```

Avoid committed generated `dist/` output unless a later distribution requirement justifies it.

Checkpoint result: `npm ci`, `npm run typecheck`, and `npm run build` pass locally with Node 22.22.2. Root Gradle tasks `frontendInstall`, `frontendTypecheck`, and `frontendBuild` reuse the root lockfile and stage the shared `frontend/playground/dist` output into the NeoForge JAR. Generated output is ignored.

NeoForge JAR audit (local): `targets/neoforge-1.21.1/build/libs/neoforge-1.21.1-0.1.0-SNAPSHOT.jar` SHA-256 `B692C2D54F44B590E79A12C54BDCEAD40D4C7C4B18635D5D6AE80F2E3BF50A52`. The packaged `index.html` (`7890228CD0F51C81390167BB16183F89759314514431280348C2316CE30BCE46`), JS (`83E40D28A8ECA5AD06E75AFB61F766931470A366B4A77868B1A0FABFC63EE0ED`), and CSS (`293DD0EDB94B0F8BC74250279F60C6D86E9176A5A3AC1337E4F2C76DE6E798AE`) hashes match the single `frontend/playground/dist` source. The JAR has 85 entries, contains common runtime classes exactly once and three web files, and has no duplicate entries, Forge adapter, frontend source, source map, or node_modules content.

The long-term parity goal remains a shared logical playground bundle, but this user-directed checkpoint audits and accepts only the NeoForge 1.21.1 JAR; Forge 1.20.1 parity is deferred while its existing structure is preserved.

Node/Vite are development/build dependencies only; players must not need them.

## 13. Rendering path

Start with correctness and instrumentation.

Required measurements/logging hooks:

- browser surface dimensions;
- paint callback count;
- uploaded pixel/byte estimate per second;
- full-frame vs dirty-region paint information if available;
- render/update timing;
- live view count;
- texture create/destroy count.

A full-frame upload implementation is acceptable only as a temporary Phase 1 path if:

- it is isolated behind the render/backend port;
- it is instrumented;
- the architecture preserves dirty-region data when available;
- the plan records measured cost for Phase 2.

Do not bake a full-frame-per-Minecraft-frame assumption into public APIs.

Checkpoint result: common `BrowserSurface` remains render-backend neutral and exposes only `FrameMetrics`; the dead `PaintFrame`/listener/uploader path and common texture ID were removed. `NeoForgeRenderableSurface.textureId()` is target-local, and the real MCEF surface subclasses `MCEFBrowser` so native paint callbacks record `paintCallbacks` and a clearly named `estimatedPaintBytes` full-frame estimate while MCEF's renderer owns the OpenGL texture path. The estimate is not reported as actual GPU upload bytes. A timed `runClient` smoke test reached CEF initialization; no interactive paint/frame sample was captured.

## 14. Resize and scale

Test at minimum:

- window resize;
- Minecraft GUI scale change;
- fullscreen toggle;
- common 16:9 sizes;
- non-16:9 window;
- HiDPI/native window scale where practical.

The browser surface should have one clearly documented mapping among:

- physical pixels;
- Minecraft GUI coordinates;
- CSS pixels.

Input and rendering must use the same mapping.

## 15. Input acceptance

Required Phase 1 behavior:

- hover/move;
- left/right click;
- wheel scroll;
- keyboard keys;
- text entry;
- Backspace/Delete/arrows;
- Tab/Shift+Tab where the browser supports it;
- Ctrl+A/C/V;
- focus enter/leave;
- Chinese IME/composition test.

If the browser backend cannot support a required IME path on one target/platform, record it as an explicit capability deviation rather than silently declaring input complete.

Checkpoint result: common input event semantics now carry key code, scan code, modifiers, text, focus, mouse, and wheel data. The single NeoForge Screen/session forwards mouse, wheel, key, text, and focus events to MCEF; mouse, wheel, keyboard, text, focus, clipboard, resize/GUI-scale, and Chinese IME remain **not manually runtime verified** in this environment, and no synthetic IME claim is made.

## 16. Security acceptance

Phase 1 bridge must verify origin/namespace before exposing privileged methods.

Negative tests should include:

- unregistered origin attempts RPC;
- unknown method;
- malformed payload;
- oversized payload boundary;
- path traversal resource request;
- RPC after view close.

Remote `http(s)` content is not part of Phase 1.

## 17. Target parity rule

The Vue playground, bridge contract, and common runtime behavior must be shared.

Forge/NeoForge may differ only in adapter implementation and explicitly documented capabilities.

Do not create:

```text
frontend/forge-playground
frontend/neoforge-playground
```

Do not duplicate common bridge/state logic into targets.

## 18. Testing gates

Before Phase 1 closes, establish commands for:

- common tests through target-safe build paths;
- Forge 1.20.1 tests/build;
- NeoForge 1.21.1 tests/build;
- frontend typecheck/tests/build;
- all-target aggregate verification.

Target manifest infrastructure may be expanded with lifecycle aliases only when the real loader Gradle plugins are installed, rather than adding fake run tasks during repository bootstrap.

Checkpoint local results: `:common:test`, `:targets:neoforge-1.21.1:test`, `testAllTargets`, `compileAllTargets`, and `buildAllTargets` pass with Gradle 8.13/ModDevGradle 2.0.141. `architectureCheck` also passes. `npm run test` passes 8 frontend tests alongside `npm ci`, `npm run typecheck`, and `npm run build`. A real `runClient` session verified F8 rendering, a connected CefQuery handshake, Java-pushed state, `demo.ping`, diagnostics, and a clean Minecraft shutdown. The preserved Forge target's existing Java test task also passes with no tests. These are local validations, not GitHub CI; the extended input/IME/lifecycle matrix remains pending.

## 19. Performance baseline

Phase 1 should collect a baseline rather than inventing unrealistic pass/fail numbers before hardware/backend behavior is known.

Measure on a documented machine/profile:

- Minecraft FPS/frame time with no web view;
- visible static web view;
- animated web view;
- CPU usage trend;
- memory before first view;
- memory after first view;
- memory after repeated open/close cycles;
- first-open latency;
- warm reopen latency;
- framebuffer upload throughput.

Phase 2 performance gates should be derived from these measurements.

Checkpoint result: lightweight `FrameMetrics` counters are present, and the real MCEF surface records paint callbacks plus a full-frame byte estimate without copying full CEF frame buffers into Java. No FPS, memory, paint-rate, or actual GPU upload-throughput numbers are reported because this run was a timed startup smoke test rather than an interactive benchmark.

## 20. Commit discipline

Use `bridge` for implementation.

Keep commits intentional and lowercase English verb phrases.

Prefer separate commits for:

1. build/dependency infrastructure;
2. common runtime contracts;
3. target adapter implementation;
4. frontend implementation;
5. tests/hardening where separable;
6. plan/docs checkpoints.

Do not use `git add .` or `git add -A` in mixed worktrees; explicitly stage intended paths.

Never force push shared `bridge` history.

## 21. Phase 1 completion checklist

Phase 1 may be marked `CLOSED / VERIFIED` only when all are true:

- [ ] Forge 1.20.1 launches the MCWebUI playground screen (out of scope for this checkpoint).
- [x] NeoForge 1.21.1 launches the MCWebUI playground screen and renders the F8 showcase.
- [x] bundled Vue assets load from the mod/JAR without external server dependency.
- [ ] mouse/wheel/keyboard/text focus work in a real game (ports implemented; runtime pending).
- [ ] Chinese input has been tested and result documented (runtime pending; no synthetic claim).
- [x] JS → Java typed RPC semantics are implemented and common-tested.
- [x] Java → Vue reactive state semantics are implemented and common-tested.
- [x] malformed/untrusted bridge calls are rejected by common policy and the trusted-origin host hook.
- [ ] screen close/reopen leaves no obvious browser/texture leak in-game (common lifecycle is tested; runtime pending).
- [ ] resize/GUI-scale path is verified in-game (translation port is compile-tested; runtime pending).
- [x] frontend production build succeeds.
- [x] NeoForge target Java/JAR build succeeds locally with the CinemaMod MCEF 2.1.6-1.21.1 coordinates.
- [ ] baseline performance metrics are recorded (instrumentation only).
- [ ] no Minecraft/loader/browser implementation types leak into common public contracts.

## 22. Next phase

After Phase 1 passes, do not immediately migrate EconomySystem wholesale.

Phase 2 should first harden runtime performance/lifecycle based on measured bottlenecks. A small EconomySystem page such as About or Balance can then serve as dogfood before Shop/Market-scale migration.
