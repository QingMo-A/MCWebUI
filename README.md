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

The current experimental milestone is a shared Vue showcase and backend-neutral Java bridge for a NeoForge 1.21.1 screen. CinemaMod MCEF `2.1.6-1.21.1` is resolved from its official release repository and wired through the official JCEF `CefMessageRouter`/`CefQuery` path. The runtime bootstrap/lifecycle acceptance checkpoint is implemented and locally automated-tested; interactive screen/input acceptance still requires a manual in-game pass.

## Current experimental status

Phase 1 scope was narrowed to **NeoForge 1.21.1** for this checkpoint. The original Forge 1.20.1 directory and manifest entry are preserved, but no new Forge runtime implementation or manual runtime result is claimed in this checkpoint.

Implemented locally:

- Java 17-compatible common runtime contracts (`WebRuntime`, `WebView`, lifecycle, input, resources, bridge, state, security, and paint metrics).
- Registry-based JSON bridge with handshake, request/response, event, explicit subscribe/unsubscribe, state update, structured errors, and origin/capability checks.
- A late-bound frontend transport: Vue/core may start before the host global exists, then binds exactly once when the private bridge-ready event arrives (with sparse bounded fallback checks). Local state listeners are registered first; host subscriptions are activated only after a successful handshake and are re-established on reconnect.
- A real browser host transport: trusted `mcui://` pages receive a small bootstrap that forwards JSON envelopes through JCEF `CefQuery`; navigation away removes the bridge and closes browser subscriptions.
- Browser READY and bridge handshake are separate protocol boundaries. Host state publication remains legal before a browser connects, while browser RPC/state operations are capability-gated after handshake. Trusted reload, untrusted navigation, and close clear globals, subscriptions, queued messages, and handshake state.
- One NeoForge `Screen` → `NeoForgeWebSession` → common `WebView`/`WebBridge` → MCEF surface path. The MCEF-native OpenGL texture path remains target-local; common no longer exposes texture IDs or a no-op input method.
- NeoForge demo handlers (`demo.ping`, `demo.counter.increment`, `demo.error`, `runtime.diagnostics`) and a responsive shared Vue showcase with controls, bridge lab, runtime telemetry, feedback overlays, and input lab.
- `NeoForgeWebSession` has deterministic once-only initialization and resize handling. Diagnostics use measured semantic names (`minecraftGuiWidth`, `browserViewportWidth`, optional framebuffer dimensions) and report `paintCallbacks` plus `estimatedPaintBytes`, never claiming estimated bytes are GPU uploads.
- A single Vue/Vite playground bundle packaged under `web/playground` in the NeoForge JAR; no localhost server is required for packaged resources.
- A Vitest regression suite reproduces the late-installed host race, verifies handshake-before-subscribe ordering, RPC/state delivery, unsubscribe/close cleanup, reconnect, and transport errors.

Known limitation: local `runClient` automatically verified NeoForge startup, MCEF loading, and CEF initialization, but no scripted key press or manual GUI session was available to verify F8 screen open, paint output, Chinese IME, clipboard, resize/GUI scale, or repeated close/reopen behavior. Chinese composition is represented in the common input model, but full GLFW IME composition is a documented target limitation. The MCEF native downloader remains owned by the MCEF mod; this repository does not vendor native binaries.

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

The playground is the `MCWebUI Runtime Showcase` page. It displays a reactive connection state, target-supplied diagnostics, the Java-pushed `demo.counter` channel, a typed `demo.ping` action, structured errors, representative controls, overlays, a scrollable feed, and an input lab for English, numbers, Chinese IME, Backspace, and Ctrl+A/C/V checks. The NeoForge adapter binds F8, serves the page from `mcui://playground`, installs the trusted host bootstrap after page load, renders the MCEF texture, and forwards scaled input. Automated runtime acceptance is complete for the bootstrap/lifecycle path; only the final interactive/manual acceptance remains.

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
