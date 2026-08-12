# MCWebUI

MCWebUI is a Minecraft web UI application framework: build modern in-game interfaces with web frontend technologies while keeping Minecraft and loader APIs behind version-specific adapters.

The project is intentionally **common-first from day one**. UI runtime contracts, bridge protocols, state synchronization, security policy, resource loading semantics, and performance policy belong in `common`; Minecraft/loader integration belongs in `targets/*`.

## Repository layout

```text
common/                       Java runtime contracts and loader-independent logic
targets/forge-1.20.1/         Forge 1.20.1 adapter
targets/neoforge-1.21.1/      NeoForge 1.21.1 adapter
frontend/packages/core/       Framework-independent TypeScript client
frontend/packages/vue/        Vue bindings
frontend/packages/components/ MC-oriented web components
frontend/playground/          Development/demo application
gradle/mcwebui-targets.json   Supported-target registry
plans/                        Architecture and execution plans
```

## Branches

- `main`: stable checkpoints and releases.
- `bridge`: active multiversion integration and development.

The current experimental milestone is a shared Vue showcase and backend-neutral Java bridge for a NeoForge 1.21.1 screen. CinemaMod MCEF `2.1.6-1.21.1` is resolved from its official release repository and wired through the official JCEF `CefMessageRouter`/`CefQuery` path. The F8 screen, bundled page, native texture, browser handshake, Java RPC/state, and runtime diagnostics have now passed a real local in-game acceptance run; extended input/lifecycle acceptance remains in progress.

## Current experimental status

Phase 1 scope was narrowed to **NeoForge 1.21.1** for this checkpoint. The original Forge 1.20.1 directory and manifest entry are preserved, but no new Forge runtime implementation or manual runtime result is claimed in this checkpoint.

Implemented locally:

- Java 17-compatible common runtime contracts (`WebRuntime`, `WebView`, lifecycle, input, resources, bridge, state, security, and paint metrics).
- Registry-based JSON bridge with handshake, request/response, event, explicit subscribe/unsubscribe, state update, structured errors, and origin/capability checks.
- A late-bound frontend transport: Vue/core may start before the host global exists, then binds exactly once when the private bridge-ready event arrives (with sparse bounded fallback checks). Local state listeners are registered first; host subscriptions are activated only after a successful handshake and are re-established on reconnect. Closing a client cancels an in-flight host wait and leaves the client disconnected; a later host install is ignored.
- A real browser host transport: the standard, secure, display-isolated `mcui` scheme is registered before CEF initialization, and trusted qualified hosts receive a small bootstrap that forwards JSON envelopes through JCEF `CefQuery`; navigation away removes the bridge and closes browser subscriptions.
- Browser READY and bridge handshake are separate protocol boundaries. Host state publication remains legal before a browser connects, while browser RPC/state operations are capability-gated after handshake. Trusted reload, untrusted navigation, and close clear globals, subscriptions, queued messages, and handshake state.
- One NeoForge `Screen` → `NeoForgeWebSession` → common `WebView`/`WebBridge` → MCEF surface path. The MCEF-native OpenGL texture path remains target-local; common no longer exposes texture IDs or a no-op input method.
- NeoForge demo handlers (`demo.ping`, `demo.counter.increment`, `demo.error`, `runtime.diagnostics`) and a responsive shared Vue showcase with controls, bridge lab, runtime telemetry, feedback overlays, and input lab.
- The showcase select control is a DOM-rendered Vue listbox rather than a native CEF popup, so choosing an option or clicking outside closes it without leaving a stale off-screen-rendering overlay.
- `NeoForgeWebSession` has deterministic once-only initialization and resize handling. Diagnostics use measured semantic names (`minecraftGuiWidth`, `browserViewportWidth`, optional framebuffer dimensions) and report `paintCallbacks` plus `estimatedPaintBytes`, never claiming estimated bytes are GPU uploads.
- The NeoForge browser keeps MCEF's persistent texture/dirty-rectangle cache, renders the full-screen surface as opaque, and does not poll diagnostics while the page is idle. The showcase's nested signal feed contains overscroll and hints its scroll position to Chromium's compositor. With `followGuiSize=true` it uses Minecraft GUI coordinates directly; the framebuffer mode uses GUI-scale-equivalent pixels when higher CSS density is needed.
- `config/mcwebui-client.toml` exposes `followGuiSize` (default `true`). Set it to `false` to use a framebuffer-equivalent viewport (`round(guiSize * guiScale)` per axis); window and GUI-scale changes resize that viewport, while X/Y input is mapped independently from current GUI coordinates.
- A single Vue/Vite playground bundle packaged under `web/playground` in the NeoForge JAR; no localhost server is required for packaged resources.
- A Vitest regression suite reproduces the late-installed host race, verifies handshake-before-subscribe ordering, RPC/state delivery, unsubscribe/close cleanup, reconnect, and transport errors.

Known limitation: the local in-game pass verified F8 screen open, bundled HTML/JS/CSS, native paint output, a connected handshake, Java-pushed initial state, `demo.ping`, populated runtime diagnostics, and both generated client-config values starting cleanly. Mouse/wheel/keyboard editing, clipboard, repeated close/reopen, and Chinese IME are not yet fully accepted. CEF windowless rendering defaults to a maximum of 30 paint callbacks per second; the MCEF/JCEF 2.1.6 Java API bundled for 1.21.1 does not expose the native frame-rate setter, so scrolling can still look less fluid than Minecraft even when the game frame rate is high. Chinese composition is represented in the common input model, but full GLFW IME composition remains a documented target limitation. MCEF 2.1.6 also emits a non-fatal `GLFW 65539` cursor warning; it did not prevent rendering or bridge operation. The MCEF native downloader remains owned by the MCEF mod; this repository does not vendor native binaries.

The Windows GAME_SYNC native checkpoint is recorded in [`plans/frame-pacing-plan.md`](plans/frame-pacing-plan.md). Exact source patches now expose an opt-in External Begin Frame creation path and the Windows amd64 JCEF JNI wrapper builds against the pinned CEF 116/5845 SDK without rebuilding Chromium. A standalone proof builder now produces a matched patched MCEF NeoForge artifact from the exact external source revisions; the client benchmark is still pending, so the result is **PROOF B**, not a runtime FPS claim. Stock mode remains unchanged and GAME_SYNC remains experimental and off by default.

## Requirements

- JDK 17 for `common` and the preserved Forge target; JDK 21 for the NeoForge target.
- Gradle 8.13 via the committed wrapper (`gradlew`/`gradlew.bat`), required by the verified NeoForge ModDevGradle 2.0.141 plugin.
- Node.js `>=20.19.0` or `>=22.12.0` and npm. The checkpoint was built with Node 22.22.2.

## Build and frontend workflow

```powershell
.\gradlew.bat :common:test --no-daemon --rerun-tasks
.\gradlew.bat :targets:neoforge-1.21.1:test --no-daemon --rerun-tasks
.\gradlew.bat compileAllTargets --no-daemon --rerun-tasks
.\gradlew.bat buildAllTargets --no-daemon --rerun-tasks

npm ci
npm run typecheck
npm run build
npm run test
```

For this checkpoint, local Gradle frontend tasks were run with Node 22.22.2 explicitly on `PATH` because the shell image does not globally register `npm.cmd`.

`frontendInstall`, `frontendTypecheck`, and `frontendBuild` are root Gradle lifecycle tasks. `frontendBuild` runs once before the NeoForge JAR task and stages `frontend/playground/dist` below `web/playground`; generated `dist/` and `node_modules/` remain ignored.

## Demo path

The playground is the `MCWebUI Runtime Showcase` page. It displays a reactive connection state, target-supplied diagnostics (including `GUI` versus `FRAMEBUFFER` viewport mode), the Java-pushed `demo.counter` channel, a typed `demo.ping` action, structured errors, representative controls, overlays, a scrollable feed, and an input lab for English, numbers, Chinese IME, Backspace, and Ctrl+A/C/V checks. The NeoForge adapter binds F8, serves the page from `mcui://playground.mcwebui`, installs the trusted host bootstrap after page load, renders the cached MCEF texture, and forwards mapped input. The core display/bridge path has passed real in-game acceptance; the remaining manual work is the extended input and repeated lifecycle matrix.

## Architecture summary

```text
Vue playground
  -> @mcwebui/vue -> @mcwebui/core
  -> JSON bridge envelope
  -> common Java WebBridge/WebStateStore/WebRuntime
  -> NeoForge adapter ports
  -> JCEF CefMessageRouter -> CinemaMod MCEF 2.1.6-1.21.1 backend
  -> Minecraft Screen + texture upload
```

See `plans/` on the `bridge` branch for the architecture and execution plan.
