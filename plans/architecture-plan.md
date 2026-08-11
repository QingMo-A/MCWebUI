# MCWebUI Architecture Plan

Status: **BOOTSTRAPPED / ACTIVE DESIGN**

Repository: `QingMo-A/MCWebUI`

Development branch: `bridge`

## 1. Product definition

MCWebUI is not a generic Minecraft browser mod. Its goal is to provide a **modern web-frontend application framework for Minecraft UI**.

The intended developer experience is:

- write UI with TypeScript, Vue, HTML, and CSS;
- bundle the web application at build time;
- ship it inside the mod JAR for offline use;
- render it inside Minecraft through an off-screen browser backend;
- communicate with Java through a typed RPC/event/state bridge;
- keep Minecraft and loader APIs out of common application semantics;
- support multiple Minecraft/loader versions from the first implementation.

MCEF/JCEF/CEF is the first intended browser backend family, but **MCWebUI itself must not become an MCEF wrapper**.

## 2. Core architectural rule

> Common owns semantics. Targets express those semantics with real Minecraft/loader/browser APIs.

Dependency direction:

```text
Vue / React / Svelte bindings
          ↓
@mcwebui/core TypeScript
          ↓
Bridge protocol
          ↓
MCWebUI common Java runtime
          ↓
Browser + Minecraft ports
          ↓
target adapters
          ↓
Forge / NeoForge / browser backend APIs
```

Common code must not import version-specific Minecraft, Forge, NeoForge, MCEF, JCEF, or CEF implementation types.

Target modules must not duplicate framework policy that belongs in common.

## 3. Repository model

```text
common/
  Loader-independent Java contracts and runtime behavior.

targets/
  forge-1.20.1/
  neoforge-1.21.1/
  Minecraft/loader adapters and backend binding.

frontend/
  packages/core/
  packages/vue/
  packages/components/
  playground/

gradle/mcwebui-targets.json
  Supported target registry.

plans/
  Architecture and phase plans.
```

`main` is the stable/release line. `bridge` is the active multiversion integration line.

## 4. Multiversion baseline

The first supported targets are:

- Forge 1.20.1 / Java 17
- NeoForge 1.21.1 / Java 21

`common` remains Java 17 compatible unless a future capability requirement proves that impossible.

`gradle/mcwebui-targets.json` is the target registry from day one so adding future versions does not require a migration from a single-version layout.

Target entries currently describe:

- target id;
- Gradle project path;
- task suffix;
- display name;
- loader;
- Minecraft version;
- Java version.

## 5. Java runtime layers

### 5.1 Common API

Stable public contracts intended for consuming mods:

- `WebRuntime`
- `WebView`
- `WebViewConfig`
- `WebResourceResolver`
- `WebBridge`
- `WebRpcRegistry`
- `WebStateStore`
- `WebPermissionPolicy`
- `FramePolicy`
- browser and render capability descriptors.

Exact names may change during Phase 1, but the boundaries should remain.

### 5.2 Browser backend port

MCWebUI must define a backend-neutral interface similar to:

```java
interface WebRuntime {
    WebView create(WebViewConfig config);
}
```

The first implementation is expected to use an MCEF/JCEF-backed runtime.

The rest of MCWebUI must not depend directly on MCEF classes.

This keeps room for future alternative backends without rewriting the bridge, state model, security policy, or frontend packages.

### 5.3 Minecraft target adapter

Target adapters own real version APIs such as:

- Minecraft `Screen` lifecycle;
- client render calls and GPU texture integration;
- native input events;
- clipboard and IME integration where available;
- resource-manager access;
- loader initialization and events;
- actual backend bootstrap dependencies;
- target-specific capability deviations.

## 6. Frontend architecture

### 6.1 `@mcwebui/core`

Framework-independent TypeScript API.

Responsibilities:

- RPC request/response;
- events;
- subscriptions;
- state synchronization;
- capability negotiation;
- bridge lifecycle and reconnect/error behavior;
- protocol serialization.

It must not depend on Vue.

### 6.2 `@mcwebui/vue`

Vue-first bindings.

Expected concepts:

```ts
const balance = useMcState<number>('balance')

const result = await mc.invoke('economy.shop.buy', {
  itemId,
  amount
})
```

Responsibilities:

- Vue composables;
- reactive state adapters;
- lifecycle cleanup;
- typed injection/context helpers.

### 6.3 `@mcwebui/components`

Reusable MC-oriented web UI components.

Long-term examples:

- panels and layout primitives;
- Minecraft-themed controls;
- item views;
- player heads;
- resource textures;
- keybind indicators;
- virtualized lists.

Native-rendered Minecraft content should not be faked as static PNGs when native rendering adds meaningful behavior such as item glint, count, NBT-derived presentation, or tooltips.

### 6.4 Other frameworks

Vue receives first-class support first, but the protocol is Java ↔ browser, not Java ↔ Vue.

Future packages may include:

- `@mcwebui/react`
- `@mcwebui/svelte`

without redesigning the Java runtime.

## 7. Web resource model

Bundled pages should be addressed through a controlled virtual scheme such as:

```text
mcui://<namespace>/index.html
mcui://<namespace>/assets/main.js
mcui://<namespace>/assets/main.css
```

The scheme should resolve resources from mod/JAR assets rather than relying on arbitrary local `file://` paths.

Goals:

- offline operation;
- deterministic packaged resources;
- namespace isolation;
- security-policy integration;
- no requirement to unpack web files to temporary directories.

The final scheme spelling and backend implementation are Phase 1 decisions.

## 8. Bridge protocol

The Java/JavaScript boundary must be structured, not a growing set of ad-hoc `window.minecraft.*` functions.

Required message classes:

- request;
- response;
- event;
- state update;
- subscribe/unsubscribe;
- capability/handshake;
- structured error.

A conceptual call:

```ts
await mc.invoke('economy.shop.buy', {
  itemId: 'minecraft:diamond',
  amount: 2
})
```

Java should register a typed handler instead of parsing a giant method switch.

Long term, API declarations should support TypeScript definition generation so bridge names and payloads are compile-time discoverable.

## 9. State model

Web UIs should not manually refresh Minecraft state after every action.

Java owns authoritative state and publishes changes to a common state store. The browser client subscribes to named state channels and exposes them reactively.

Desired semantics:

```text
Java state update
      ↓
common state store
      ↓
bridge delta/event
      ↓
@mcwebui/core
      ↓
Vue ref/computed update
```

The protocol must distinguish authoritative state from local presentation state.

## 10. Security model

Security is a first-version concern because JavaScript-to-Java bridging is an execution boundary.

Defaults:

- arbitrary `http://` and `https://` origins do not receive privileged Java bridge access;
- only registered local MCWebUI namespaces/origins may receive capabilities;
- APIs are capability/permission whitelisted;
- no generic Java reflection bridge;
- no arbitrary process execution;
- no arbitrary filesystem access;
- no raw unrestricted network bridge;
- external network access is disabled by default for bundled mod UI;
- client UI is never authoritative for multiplayer economy/gameplay state;
- server-side actions must validate all client requests again.

Future explicit external-web permission should be opt-in and visible to users/modpack authors.

## 11. Rendering and performance model

Performance determines whether MCWebUI is useful.

### 11.1 Browser lifecycle

The browser engine/runtime should initialize once per game process rather than once per screen.

Web views should be managed through a lifecycle/pool instead of blindly creating and destroying Chromium instances on every screen transition.

Conceptual states:

```text
ACTIVE → WARM → SUSPENDED → DESTROYED
```

Exact pool policy must be measured before becoming public API.

### 11.2 Frame scheduling

Target behavior:

- interactive/animated: high refresh, normally up to 60 FPS initially;
- static visible page: aggressively reduced work;
- unchanged/suspended page: zero or near-zero repaint/upload work;
- background page: suspended unless a declared capability requires otherwise.

`FramePolicy.ADAPTIVE` is the preferred public direction.

### 11.3 Dirty-region upload

Do not design around uploading an entire high-resolution browser framebuffer every Minecraft frame.

The browser paint path should preserve dirty rectangles where the backend exposes them and upload only changed regions when practical.

Pipeline target:

```text
browser paint
  ↓
dirty rectangles
  ↓
CPU pixel buffer
  ↓
texture upload scheduler
  ↓
Minecraft GPU texture
```

Full-frame fallback is acceptable initially if required for the first vertical slice, but it must be measurable and replaceable without changing frontend APIs.

### 11.4 Web content guidance

MCWebUI should eventually provide performance guidance/tooling for:

- excessive large blur/backdrop filters;
- pathological box shadows;
- huge DOM trees;
- unvirtualized long lists;
- needless animation while hidden.

Large data UIs such as markets should use list virtualization.

## 12. Input model

Target adapters translate Minecraft input into backend-neutral web input events.

Required categories:

- pointer move;
- mouse down/up;
- wheel;
- keyboard press/release;
- character input;
- focus;
- clipboard;
- drag where practical;
- IME/composition, with Chinese input explicitly tested.

Input coordinates must account for Minecraft GUI scale, window scale, and browser surface resolution.

## 13. Screen and future surface model

Phase 1 focuses on normal Minecraft `Screen` integration.

The runtime architecture should not permanently assume that all web views are screens. Future surfaces may include:

- HUD overlays;
- pause/menu widgets;
- world-space displays/panels;
- editor/tool windows.

Surface-specific Minecraft APIs belong in target adapters.

## 14. Native overlay model

Some content is better rendered by Minecraft than Chromium.

Future native overlay candidates:

- ItemStack rendering;
- enchantment glint;
- native item tooltip support;
- player heads/skins;
- ResourceLocation textures where useful.

A future web component such as `<McItemStack>` should describe semantics while a target-native overlay renderer performs the real Minecraft draw.

This is not required for the first browser vertical slice.

## 15. Build model

Web applications are compiled during development/build, not at Minecraft runtime.

Expected flow:

```text
Vue/TS/CSS
   ↓
Vite/build tooling
   ↓
HTML + JS + CSS assets
   ↓
mod resources
   ↓
JAR
   ↓
mcui:// resource resolver
```

Minecraft runtime must not need the Vue template compiler or Node.js.

## 16. Testing strategy

### Common tests

- bridge envelope and serialization;
- request/response correlation;
- state subscriptions and delta behavior;
- permission/capability policy;
- lifecycle state machine;
- frame scheduling policy;
- resource path normalization/security.

### Target tests

- adapter contract parity;
- input coordinate conversion;
- loader/bootstrap lifecycle;
- resource integration;
- render/texture lifecycle where testable;
- target capability deviations.

### Frontend tests

- RPC client behavior;
- state subscriptions;
- Vue composable lifecycle;
- error handling;
- component behavior.

### Manual/performance tests

- FPS/frame time;
- CPU load while static;
- texture upload bandwidth;
- memory after repeated open/close cycles;
- first-open latency;
- warm reopen latency;
- resize/GUI scale changes;
- Chinese IME;
- clipboard;
- fullscreen/windowed transitions.

## 17. Phase 1 implementation decisions (NeoForge bridge checkpoint)

The NeoForge 1.21.1 vertical slice now uses the official JCEF message-router transport. A trusted `mcui://<namespace>` page receives a private browser bootstrap after main-frame load; `send()` calls `window.cefQuery` with a JSON envelope, and Java responses/events/state are delivered back through a narrowly scoped `executeJavaScript` callback. The load hook removes the globals and closes host subscriptions when navigation leaves the trusted origin. No reflection, URL polling, localhost server, clipboard IPC, or arbitrary native bridge is used.

State subscription is an explicit protocol operation (`subscribe`/`unsubscribe`). The frontend emits subscribe on the first local listener and unsubscribe after the last listener; the target host maps each channel to one `WebBridge` subscription, preserving latest-value semantics and close/reload cleanup.

The frontend transport is deliberately late-bound. A `DefaultMcWebClient` may be constructed before the target installs `window.__MCWEBUI_BRIDGE__`; `DeferredWindowBridgeTransport` resolves the current global at connect/send time, listens for a private ready event, and performs only sparse bounded fallback checks. A local subscription is never sent to the host before handshake, and reconnect replays active channels without duplicate host subscriptions. This keeps the public Vue layer independent of MCEF/JCEF load timing.

`WebView.initialize()` reaching `READY` is not browser handshake. The host may publish authoritative state into its store before a browser connects, while browser-origin RPC and state subscription requests require the capabilities negotiated by `WebBridge.handshake()`. Trusted reload, untrusted navigation, and close reset handshake state, remove bridge globals, release host subscriptions, and discard stale queued messages.

The active target composition is one `NeoForgeMinecraftScreen` -> `NeoForgeWebSession` -> common `WebView`/`WebBridge` -> `NeoForgeMcefBackend` path. Common `BrowserSurface` no longer exposes an OpenGL texture ID, and the old mandatory Java pixel-frame listener/uploader contract was removed because MCEF uploads its native texture directly. NeoForge's `NeoForgeRenderableSurface` owns that target-local capability. `WebView` has no public no-op `dispatchInput`; the browser surface is the input endpoint.

The shared showcase obtains target/backend/version/metrics through the registered `runtime.diagnostics` RPC. Frontend source does not hardcode a loader identity, so a future Forge adapter can provide the same logical shape.

For the NeoForge 1.21.1 adapter, `Screen.resize` was checked against the mapped 21.1.216 source and does not invoke `init`; `NeoForgeWebSession` still treats initialization as once-only and cleans partial failures to prevent duplicate WebViews or MCEF browsers. `BrowserBackend.createSurface` requires a non-null bridge. Paint metrics expose callback counts and an explicitly estimated full-frame byte total; they do not claim actual GPU upload bytes.

## 18. Initial roadmap

### Phase 0 — Repository bootstrap

Status: **DONE**

- common/targets structure from day one;
- Forge 1.20.1 and NeoForge 1.21.1 target registry;
- Java version boundaries;
- frontend workspace skeleton;
- architecture plans.

### Phase 1 — Browser runtime vertical slice

Goal:

> bundled Vue page renders in a Minecraft Screen, accepts input, performs typed JS↔Java RPC, receives Java state updates, and works offline from packaged resources.

See `plans/phase-1-browser-runtime-plan.md`.

### Phase 2 — Runtime hardening

Expected topics:

- browser pooling;
- adaptive frame policy;
- dirty-region upload optimization;
- robust resize/focus lifecycle;
- security/capability hardening;
- profiling and regression tests.

### Phase 3 — Developer SDK

Expected topics:

- stable Java public API;
- stable `@mcwebui/core`;
- stable `@mcwebui/vue`;
- developer template/scaffolding;
- typed API generation;
- documentation/examples.

### Phase 4 — Minecraft-native components

Expected topics:

- ItemStack overlay;
- player head/skin;
- ResourceLocation integration;
- native tooltip/keybind abstractions;
- reusable component library.

### Phase 5 — Dogfood and ecosystem

- integrate a contained EconomySystem page first;
- compare against the existing native UI baseline;
- validate performance and ergonomics in a real mod;
- only then consider larger migrations.

## 19. Non-goals for early phases

Do not initially attempt to:

- maintain a custom Chromium fork;
- replace MCEF/JCEF internals;
- support arbitrary internet browsing;
- migrate all EconomySystem UI;
- support every frontend framework;
- build world-space displays before Screen runtime is stable;
- reproduce every native Minecraft widget before the bridge/runtime is proven.

## 20. Architecture reopen rule

Do not continuously rewrite this architecture for aesthetic purity.

Change a boundary only when a real implementation, new target, security requirement, or measured performance problem proves the current abstraction insufficient.

The first real judge of the architecture is Phase 1 plus a real dogfood UI, not diagram elegance.
