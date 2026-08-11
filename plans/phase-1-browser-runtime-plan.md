# Phase 1 — Browser Runtime Vertical Slice

Status: **PLANNED**

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

- [ ] Forge 1.20.1 launches the MCWebUI playground screen.
- [ ] NeoForge 1.21.1 launches the same logical playground screen.
- [ ] bundled Vue assets load from the mod/JAR without external server dependency.
- [ ] mouse/wheel/keyboard/text focus work.
- [ ] Chinese input has been tested and result documented.
- [ ] JS → Java typed RPC works.
- [ ] Java → Vue reactive state works.
- [ ] malformed/untrusted bridge calls are rejected.
- [ ] screen close/reopen leaves no obvious browser/texture leak.
- [ ] resize/GUI-scale path is verified.
- [ ] frontend production build succeeds.
- [ ] both target builds succeed.
- [ ] baseline performance metrics are recorded.
- [ ] no Minecraft/loader/browser implementation types leak into common public contracts.

## 22. Next phase

After Phase 1 passes, do not immediately migrate EconomySystem wholesale.

Phase 2 should first harden runtime performance/lifecycle based on measured bottlenecks. A small EconomySystem page such as About or Balance can then serve as dogfood before Shop/Market-scale migration.
