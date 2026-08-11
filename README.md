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

The current experimental milestone is a bundled Vue page and backend-neutral Java bridge for a NeoForge 1.21.1 screen. The implementation proves the common contracts, RPC/state/resource semantics, input translation ports, and reproducible frontend/JAR build. A maintained MCEF artifact for NeoForge 1.21.1 is not currently available from the verified upstream line, so native game launch remains an explicit follow-up blocker rather than an unverified claim.

## Current experimental status

Phase 1 scope was narrowed to **NeoForge 1.21.1** for this checkpoint. The original Forge 1.20.1 directory and manifest entry are preserved, but no new Forge runtime implementation or manual runtime result is claimed in this checkpoint.

Implemented locally:

- Java 17-compatible common runtime contracts (`WebRuntime`, `WebView`, lifecycle, input, resources, bridge, state, security, and paint metrics).
- Registry-based JSON bridge with handshake, request/response, event, state update, structured errors, and origin/capability checks.
- NeoForge target-owned adapter seams (`NeoForgeWebScreen`, texture upload and browser backend ports) and explicit demo handlers (`demo.ping`, `demo.counter.increment`).
- A single Vue/Vite playground bundle packaged under `web/playground` in the NeoForge JAR; no localhost server is required for packaged resources.

Known limitation: the verified CCBlueX MCEF line currently publishes `3.1.0-1.21.4` through JitPack, not NeoForge 1.21.1. Until a compatible, maintained backend artifact is selected and verified, the target backend fails explicitly instead of silently presenting a fake browser runtime. Chinese IME, clipboard, screen rendering, and close/reopen behavior therefore still require manual in-game verification after that backend is available.

## Requirements

- JDK 17 for `common` and the preserved Forge target; JDK 21 for the NeoForge target.
- Gradle 8.8 via the committed wrapper (`gradlew`/`gradlew.bat`).
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
```

`frontendInstall`, `frontendTypecheck`, and `frontendBuild` are root Gradle lifecycle tasks. `frontendBuild` runs once before the NeoForge JAR task and stages `frontend/playground/dist` below `web/playground`; generated `dist/` and `node_modules/` remain ignored.

## Demo path

The playground is the `MCWebUI Runtime Demo` page. It displays target/bridge status, the Java-pushed `demo.counter` channel, a typed `demo.ping` action, and an input field for English, numbers, Chinese IME, Backspace, and Ctrl+A/C/V checks. The target adapter exposes the screen lifecycle and coordinate-scale translation; the native loader keybind wiring and real browser surface are pending the verified MCEF backend selection.

## Architecture summary

```text
Vue playground
  -> @mcwebui/vue -> @mcwebui/core
  -> JSON bridge envelope
  -> common Java WebBridge/WebStateStore/WebRuntime
  -> NeoForge adapter ports
  -> MCEF/JCEF backend (selection blocked for NeoForge 1.21.1)
  -> Minecraft Screen + texture upload
```

See `plans/` on the `bridge` branch for the architecture and execution plan.
