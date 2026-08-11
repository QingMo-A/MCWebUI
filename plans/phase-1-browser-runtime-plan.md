# Phase 1 — Browser Runtime Vertical Slice

Status: **IMPLEMENTED / PARTIALLY RUNTIME VERIFIED (NeoForge-only scope)**

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

The user-directed checkpoint narrows new implementation and runtime acceptance to **NeoForge 1.21.1**. The Forge 1.20.1 module and manifest entry remain untouched as preserved project structure; no Forge runtime result is claimed here. The NeoForge native browser launch is blocked by dependency availability documented below. Common contracts and frontend semantics are implemented and locally tested without claiming an in-game launch.

Implementation checkpoint commit: `5511d16` (`implement phase one browser runtime slice`).

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
| Gradle wrapper | 8.8 | [Gradle distribution](https://services.gradle.org/distributions/) | Compatible with the local JDK 17/21 toolchains and the verified NeoForge ModDevGradle line. |
| ForgeGradle | `[6.0,6.2)` (research only) | [ForgeGradle 6.x docs](https://docs.minecraftforge.net/en/fg-6.x/) | Official range for Forge 1.20.1; Forge implementation is out of this scope checkpoint and is not applied. |
| NeoForge build plugin | ModDevGradle `1.0.11` (research candidate) | [NeoForged ModDevGradle](https://github.com/neoforged/ModDevGradle) | Official plugin documents Gradle 8.8 compatibility and Java 21; not applied to a native run because the browser backend gate is unresolved. |
| MCEF backend | CCBlueX `com.github.CCBlueX:mcef:3.1.0-1.21.4` (JitPack candidate) | [CCBlueX/mcef README](https://github.com/CCBlueX/mcef) | Maintained fork and native downloader are verified, but the published candidate targets 1.21.4, not NeoForge 1.21.1. It is therefore not declared as a fake/incompatible dependency; `NeoForgeMcefBackend` fails explicitly until a 1.21.1 artifact is verified. |
| Forge MCEF reference | MCEF `2.1.6-1.20.1` (artifact listing only) | [CurseForge files](https://www.curseforge.com/minecraft/mc-mods/mcef/files/all?page=1&pageSize=20&version=1.20.1) | Confirms a historical Forge 1.20.1 build, but does not establish a shared backend with NeoForge 1.21.1 and is outside this checkpoint. |
| Vue | `3.5.41` | [npm Vue registry](https://registry.npmjs.org/vue/latest) | Current stable Vue 3 release used by the shared playground. |
| Vite | `8.2.1` | [npm Vite registry](https://registry.npmjs.org/vite/latest) and [Vite compatibility docs](https://vite.dev/guide/) | Current stable release; requires Node `^20.19.0 || >=22.12.0`. |
| TypeScript | `7.0.2` | [npm TypeScript registry](https://registry.npmjs.org/typescript/latest) | Current stable compiler used for core/vue/playground typechecks. |
| Node.js | `22.22.2` for local validation; requirement `>=20.19.0 || >=22.12.0` | [Node.js release archive](https://nodejs.org/en/download/archive/v22) | Satisfies Vite's documented engine requirement. |

MCEF/JCEF native binaries are downloaded by the selected MCEF fork's own bootstrap/downloader; this repository does not copy unverified native binaries. The verified NeoForge 1.21.1 artifact gap is a real blocker for launching Minecraft, not a reason to leak MCEF types into common.

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
mcui://playground/index.html
mcui://playground/assets/...
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

Checkpoint result: `@mcwebui/core` implements `connect`, `invoke`, `on`, and `subscribe` with request IDs, pending Promise correlation, structured errors, event dispatch, state subscriptions, and a private `window.__MCWEBUI_BRIDGE__` transport adapter. `@mcwebui/vue` provides `useMcBridge`, `useMcState`, and `useMcRpc` without reimplementing transport. The playground is a real Vite/Vue app and is the only frontend bundle source of truth.

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

Checkpoint result: `npm ci`, `npm run typecheck`, and `npm run build` pass locally. Root Gradle tasks `frontendInstall`, `frontendTypecheck`, and `frontendBuild` reuse the root lockfile and stage the shared `frontend/playground/dist` output into the NeoForge JAR. Generated output is ignored.

NeoForge JAR audit (local): `targets/neoforge-1.21.1/build/libs/neoforge-1.21.1-0.1.0-SNAPSHOT.jar` SHA-256 `BBB0E8C1DCAE25720A20F2CB5E89EED5CA29753A7089E750C552BA46994711CC`. The packaged `index.html`, JS (`20F7346024F25C2A28020D145F108064D0A703305045A7496EBB3AA8AFE09808`), and CSS (`4F84F12437DB4BDFFB161196FA69200237FDF879B9CECFD3DA032B1372BFBA0E`) hashes match the single `frontend/playground/dist` source. The JAR contains common runtime classes, only the NeoForge adapter, `META-INF/neoforge.mods.toml`, and `web/playground` resources.

The final target JAR should contain the same logical playground bundle on both supported targets.

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

Checkpoint result: common `BrowserSurface`, `PaintFrame`, `DirtyRect`, `FrameMetrics`, and target texture-uploader ports are implemented. No native MCEF paint callback was executed because the NeoForge 1.21.1 backend candidate is unavailable; paint/upload counters are instrumentation hooks for the next runtime checkpoint.

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

Checkpoint result: common input event semantics and NeoForge coordinate/GUI-scale translation are implemented and compile-tested. Mouse, wheel, keyboard, text, focus, clipboard, and Chinese IME remain **not runtime verified** until a real NeoForge browser surface is available; no synthetic `charTyped` claim is made.

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

Checkpoint local results: `:common:test`, `:targets:neoforge-1.21.1:test`, `testAllTargets`, `compileAllTargets`, and `buildAllTargets` pass. The preserved Forge target's existing Java test task also passes with no tests. These are local Gradle validations, not GitHub CI. `architectureCheck` verifies common has no Minecraft/loader/browser implementation imports and that no target-specific frontend source trees exist.

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

Checkpoint result: only the lightweight `FrameMetrics` counters and dirty-rectangle data path are present. No FPS, memory, paint-rate, or upload-throughput numbers are reported because Minecraft could not be launched without a verified NeoForge 1.21.1 MCEF backend.

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
- [ ] NeoForge 1.21.1 launches the MCWebUI playground screen (blocked by verified MCEF artifact availability).
- [ ] bundled Vue assets load from the mod/JAR without external server dependency.
- [ ] mouse/wheel/keyboard/text focus work in a real game (ports implemented; runtime pending).
- [ ] Chinese input has been tested and result documented (runtime pending; no synthetic claim).
- [x] JS → Java typed RPC semantics are implemented and common-tested.
- [x] Java → Vue reactive state semantics are implemented and common-tested.
- [ ] malformed/untrusted bridge calls are rejected.
- [ ] screen close/reopen leaves no obvious browser/texture leak in-game (common lifecycle is tested; runtime pending).
- [ ] resize/GUI-scale path is verified in-game (translation port is compile-tested; runtime pending).
- [x] frontend production build succeeds.
- [x] NeoForge target Java/JAR build succeeds locally; native loader build is blocked by MCEF selection.
- [ ] baseline performance metrics are recorded (instrumentation only).
- [ ] no Minecraft/loader/browser implementation types leak into common public contracts.

## 22. Next phase

After Phase 1 passes, do not immediately migrate EconomySystem wholesale.

Phase 2 should first harden runtime performance/lifecycle based on measured bottlenecks. A small EconomySystem page such as About or Balance can then serve as dogfood before Shop/Market-scale migration.
