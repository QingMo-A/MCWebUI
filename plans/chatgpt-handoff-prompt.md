# MCWebUI ChatGPT Project Handoff Prompt

Status: **ACTIVE PROJECT ENTRYPOINT**

This file is the durable conversation handoff for future ChatGPT sessions. After every major architecture or phase checkpoint, update the **Current checkpoint** section without rewriting stable project rules.

## Paste-ready prompt

You are taking over technical design/review work for the Minecraft mod/framework project **MCWebUI**.

Repository: `https://github.com/QingMo-A/MCWebUI`

Active development branch: `bridge`

Stable/release branch: `main`

The user is the project owner. Respond in Chinese. Your role is primarily architecture partner, reviewer, planner, and Codex prompt author. Codex performs most source edits. You may directly maintain durable plans/handoff documents in `plans/` when useful.

### First action in every new conversation

Do not answer from this handoff alone. Inspect the actual remote `bridge` branch first with the GitHub connector, then read at minimum:

- `plans/chatgpt-handoff-prompt.md`
- `plans/architecture-plan.md`
- `plans/phase-1-browser-runtime-plan.md`
- `README.md`
- `gradle/mcwebui-targets.json`
- `settings.gradle`
- `build.gradle`
- the latest relevant commits/diff on `bridge`

Treat the **Current checkpoint** below as a navigation hint, not source of truth. Remote code and git history win if they differ.

### Product definition

MCWebUI is **not merely a browser mod**. It is intended to become a modern Minecraft Web UI application framework:

- frontend written with TypeScript / Vue / HTML / CSS;
- frontend bundled at build time and shipped inside mod JARs;
- off-screen browser backend renders into Minecraft;
- Java ↔ web communication uses an explicit typed RPC/event/state bridge;
- Minecraft/loader/browser implementation APIs stay behind version adapters;
- frontend and common semantics remain shared across supported targets;
- future framework bindings may include React/Svelte, but Vue is the first-class initial binding.

### Architectural invariant

`Common owns semantics. Targets express those semantics with real APIs.`

Desired dependency direction:

```text
Vue / future bindings
        ↓
@mcwebui/core TypeScript
        ↓
bridge protocol
        ↓
MCWebUI common Java
        ↓
ports/contracts
        ↓
target adapters
        ↓
Minecraft + loader + browser backend APIs
```

Common must remain Java 17 compatible and must not expose/import Minecraft, Forge, NeoForge, MCEF, JCEF, or CEF implementation types.

Target Minecraft `Screen` classes must remain version-specific and delegate framework semantics rather than duplicating them.

Supported target registry lives in `gradle/mcwebui-targets.json`. The initial targets are Forge 1.20.1 (Java 17) and NeoForge 1.21.1 (Java 21). It is acceptable to implement/verify one target first **only if the shared common/frontend/port design remains capable of receiving the other target without migration**.

### Build / git workflow

- Work on `bridge`; do not casually modify `main`.
- Before a Codex task, inspect current remote HEAD and pin the prompt to the exact base SHA.
- Keep commit messages lowercase English verb phrases; do not require Conventional Commit prefixes.
- Prefer an implementation commit followed by a documentation checkpoint commit when the change is substantial.
- Explicit staging only. Never instruct `git add .` or `git add -A`.
- Never force-push.
- Push with `git push origin HEAD:bridge` and verify local SHA equals remote SHA.
- Call local Gradle/npm verification exactly that: local verification. Do not call it GitHub CI unless Actions actually ran.
- Do not fabricate manual Minecraft/IME/runtime acceptance results.

### Review philosophy

When the user says “看看效果”, inspect the actual remote branch/diff/files. Do not trust a Codex narrative at face value.

Review especially for:

- common accidentally depending on target/browser APIs;
- target code duplicating bridge/runtime/frontend semantics;
- target-specific frontend bundles;
- dead/parallel adapter paths that can drift;
- fake or unconnected JS↔Java bridge transports;
- lifecycle/resource leaks;
- GUI scale/input coordinate errors;
- claims of IME/runtime verification not supported by evidence;
- build scripts that make future target onboarding require architecture migration.

After review, give a concrete verdict and normally one autonomous professional Codex prompt pinned to current remote HEAD.

## Current checkpoint

Checkpoint date: **2026-08-14**

Historical branch HEAD before this handoff file was added: `2a6fb16f5586e2809c3313d943b01fee8402c3dd` (`clarify neoforge scope checkpoint`). Always re-fetch current `bridge` before acting.

Runtime distribution state: **PHASE A-B IMPLEMENTED / PHASE C CORE IMPLEMENTED / RELEASE PIPELINE READY / PRODUCTION SOURCE NOT CONFIGURED** (checkpoints 15-18 below).

Phase 1 status in the plan: **IMPLEMENTED / PARTIALLY RUNTIME VERIFIED (NeoForge-only bridge/showcase scope)**.

What currently exists:

- loader/browser-neutral common runtime contracts;
- common bridge codec/dispatcher/state/security/resource/input semantics, including explicit subscribe/unsubscribe operations;
- `@mcwebui/core` TypeScript client;
- `@mcwebui/vue` bindings;
- one shared Vue/Vite playground source;
- `mcui://` JAR resource loading for NeoForge;
- CinemaMod MCEF backend for NeoForge 1.21.1 with official JCEF `CefMessageRouter`/`CefQuery` JS-to-Java transport;
- a single NeoForge Minecraft Screen -> `NeoForgeWebSession` path rendering the target-local MCEF texture;
- a responsive shared showcase with bridge lab, runtime diagnostics, controls, overlays, scroll area, and input lab;
- manifest still retains both NeoForge 1.21.1 and Forge 1.20.1;
- Forge implementation/runtime parity is intentionally deferred, not removed.

Implementation checkpoints: `4ad22a1` (`wire neoforge browser bridge and showcase`), `c494056` (`fix bridge bootstrap lifecycle and runtime acceptance`), `9b28e6a` (`preserve disconnected state on bridge close`), and `c725903` (`fix neoforge bundled runtime display`). The latest implementation closes the late host bootstrap race, makes connection precede host subscriptions, registers a real qualified `mcui` origin before CEF startup, stages the frontend into the dev runtime, fixes resource response lengths/render winding, accepts the minimal browser handshake, and hardens reload/close/session lifecycle. Documentation checkpoint is recorded in the following commit (`see git history`).

Important review findings at this checkpoint:

1. **NeoForge-only implementation is still within the intended bridge model**, because common/frontend semantics remain shared and the Forge target/manifest entry remains preserved. Do not treat “one target implemented first” as a failure by itself.
2. NeoForge screen/runtime paths are converged: F8 opens `NeoForgeMinecraftScreen`, which delegates lifecycle/input/rendering to one `NeoForgeWebSession`; the former duplicate `NeoForgeWebScreen`/`NeoForgeRuntimeAdapter`/frame uploader paths were removed.
3. `targets/neoforge-1.21.1/build.gradle` currently adds common Java sources directly to its main source set and also merges the common JAR. Investigate whether ModDevGradle supports a cleaner project/shared-source arrangement, but do not break a working dev runtime merely for aesthetic purity.
4. The browser host transport is now real and late-bound: a load hook installs `window.__MCWEBUI_BRIDGE__` only for the configured trusted `mcui://` origin; its `send()` uses JCEF `CefQuery` JSON envelopes and Java events/state are delivered with `executeJavaScript`. The frontend may start before that global appears, then binds on the private ready event. Navigation away removes globals, queued messages, handshake state, and host subscriptions.
5. `WebView.initialize()` reaches READY without negotiating browser capabilities. Browser handshake then gates RPC/state operations; host state publication remains legal before connection. `NeoForgeWebSession` initialization is once-only and diagnostics distinguish GUI/viewport/framebuffer dimensions and estimated paint bytes.
6. The NeoForge core runtime has a real in-game acceptance result: F8 renders the shared showcase, the connection reaches `Connected`, initial Java state and `demo.ping` cross CefQuery, runtime diagnostics populate, and Minecraft exits cleanly. Mouse/wheel/keyboard editing, clipboard, resize/GUI-scale, repeated close/reopen, and Chinese IME remain explicitly not fully verified.
7. The current performance checkpoint preserves MCEF's persistent texture/dirty-rectangle cache, removes the playground's 800 ms diagnostics repaint loop, uses GUI-sized opaque browser surfaces by default, and exposes `config/mcwebui-client.toml` `followGuiSize`. Framebuffer mode uses `round(guiSize * guiScale)` per axis as the window changes and maps each input axis by ratio. The bundled MCEF/JCEF 2.1.6 API has no public windowless frame-rate setter, so CEF's default 30 FPS OSR ceiling remains a documented visual limit rather than being bypassed with reflection.
8. The GAME_SYNC/frame-pacing checkpoint is captured in `plans/frame-pacing-plan.md`. Exact source patches add an opt-in JCEF/MCEF external-begin-frame path, and the Windows amd64 JCEF JNI wrapper builds against CEF 116/5845. A standalone ModDevGradle proof builder now creates the patched MCEF NeoForge jar from exact MCEF/JCEF revisions (PROOF B); no patched client run or fabricated FPS result is permitted.

9. The 2026-08-12 Direct CEF checkpoint adds an isolated modern CEF 144
   three-slot GPU mailbox. `OnAcceleratedPaint` performs
   `OpenSharedResource1` plus a host-owned `CopyResource` and publishes a
   generation; an independent consumer composites and calls `Present(0)` or
   `Present(1)` outside the producer lock. Clean 1280x720 runs reached
   independent 60/120/144 presents while accelerated delivery remained about
   54--58/s. The exact observed descriptor was BGRA8 (`DXGI` numeric 87), with
   CPU access flags 0. CEF 5845 remains the CPU-only historical baseline.
   Producer/present accounting, ten serial lifecycle runs, and local Gradle
   frontend/target verification passed. A five-pixel synthetic alpha shader
   check passed 5/5, the real CEF144 premultiplied BGRA raw/composition alpha
   matrix passed, and the native HWND-subclass automated input matrix passed
   (including modal Escape close). Rounded-corner/world visual inspection,
   IME/clipboard, and physical scanout remain **READY FOR USER ACCEPTANCE**.
   At that checkpoint the isolated proof was **READY FOR D3D/OpenGL INTEROP
   PROOF** only; checkpoint 12 below records the subsequent standalone
   interop result. Do not integrate it into Minecraft or claim 120/144
   distinct browser generations.

10. The modern CEF high-refresh recheck supersedes the earlier rate conclusion
    for this question. Checkpoint `d992347` requested 120/144 but configured
    `windowless_frame_rate=60`, so its high-refresh result is retained as
    **SUPERSEDED / INCONCLUSIVE**, not deleted. The isolated proof now keeps the
    CEF 5845 1..60 compatibility clamp and uses the requested target for CEF
    144, recording `requestedTargetHz` and
    `configuredWindowlessFrameRate` in each JSON result. At 1280x720,
    configured 60/120/144 yielded approximately 54.6/64.3/64.1 published
    generations/s and 59.9/120.0/144.0 independent presents/s. The required
    target-144 A/B yielded 56.7 published/s at configured 60 versus 64.0 at
    configured 144. This is **VERDICT B**: the old clamp was a real bottleneck,
    but the host's modern accelerated-generation ceiling remains about 64 Hz.

11. The transparent alpha proof is independent of the historical synthetic
    `alphaAcceptance` field. With `--alpha-proof`, CEF144 receives a transparent
    `CefBrowserSettings.background_color`; one bounded raw readback per fixed
    world/alpha sample records premultiplied BGRA, then a fixed-blue GPU shader
    verifies final composition. JSON records `realCefAlphaAcceptance` and
    `alphaModel=premultiplied`; all five raw and five composition samples passed
    on 2026-08-13. CEF5845 compatibility remains build/smoke verified with the
    1..60 clamp. Human visual acceptance is manual and is not an automated PASS.

12. The standalone opengl-interop proof passes on the local NVIDIA GeForce RTX
    5060 Ti host: WGL_NV_DX_interop/interop2 and all entry points are present,
    wglDXOpenDeviceNV succeeds, and the existing three-slot D3D mailbox is
    consumed through read-only GL registrations. Full-frame fixed-blue
    composition passes all five real CEF alpha samples with RGBA mapping and
    textureYFlipped=true; native input, 60/120/144 GL smokes, ten bounded
    lifecycles, and teardown pass. This is NVIDIA-only evidence: AMD/Intel
    fallback is not implemented, and missing capability remains
    UNSUPPORTED/FAILED without CPU fallback. Manual visual inspection remains
    READY FOR USER ACCEPTANCE; do not claim Minecraft/JNI integration. See
    plans/d3d-opengl-interop-plan.md.

13. The 2026-08-13 NeoForge 1.21.1 Direct CEF slice is opt-in only. The
    default `mcef` backend remains unchanged; `mcwebui.browserBackend=direct-cef`
    selects an external CEF144 DLL/helper and the proof runner's
    `mcwebuiDirectCefProof` property removes the MCEF runtime dependency so two
    process-global CEF versions are never loaded together. Native CEF owns the
    asynchronous accelerated D3D mailbox, while the target-local surface
    contract exposes `beginRenderFrame`/`textureId`/`endRenderFrame`,
    `PREMULTIPLIED` alpha, and `yFlipped` for the Minecraft render thread.
    The renderer/browser message routers and Java DirectBridgeHost now carry
    the real bridge; production native packaging remains NOT IMPLEMENTED.
    A process-owned loopback server serves bundled resources on an ephemeral
    port and random path, while the helper path remains explicit.

    The retained Direct session is now prewarmed on the Minecraft client/render
    tick: before F8 it loads the real Vue page, completes the Java bridge
    handshake, and obtains the first accelerated texture without taking focus.
    This moved the observed first-F8 freeze into the normal loading phase without
    violating CEF/WGL thread ownership. Direct external BeginFrame requests are
    fractionally capped at configured 60/120/144 Hz, so a 180 Hz host render loop
    no longer implies 180 browser requests; game signals, rAF, accelerated
    generations and presents are explicitly different measurements.

    Once hidden prewarm completes, `WasHidden(true)` stops external frame
    requests, accelerated paints, GPU copies and GL locks. Idle CPU work is
    limited to a bounded empty bridge poll per client tick and idle runtime
    threads; the intentional cost is resident CEF processes plus roughly one
    2560x1418 BGRA host texture (13.85 MiB at the observed viewport). Closing the
    retained runtime could save memory but would restore the first-F8 hitch. A
    document visibility hook now stops a running Animation Lab when the screen
    hides. The showcase also includes a persistent 0-100% WebScreen opacity
    slider that exercises the Direct premultiplied-alpha world reveal while
    remaining recoverable at 0%.

    Native CEF144 lifecycle smoke, Java/NeoForge tests, frontend/all-target
    builds, direct class-path startup, bundled Vue loading, renderer bootstrap,
    CefQuery transport and Java handshake passed. The user also observed the
    Direct page and input in Minecraft. Therefore this checkpoint remains
    **VERDICT B** for production: the automated Minecraft fullscreen/WGL
    matrix and native distribution are still pending. Do not generalize the
    standalone NVIDIA interop result to other vendors. See
    `plans/direct-cef-neoforge-plan.md`.

14. The Direct CEF NeoForge path is now treated as an experimental runtime,
    not an unknown Minecraft feasibility proof. The user has run the real F8
    surface, bridge and input path; its status is **USER RUNTIME VERIFIED /
    AUTOMATED REGRESSION PENDING**. Production native distribution and broad
    hardware support remain NOT IMPLEMENTED.

    The target-local surface contract now owns `OPAQUE` versus
    `PREMULTIPLIED` semantics. Direct CEF uses typed Minecraft blend factors
    `ONE / ONE_MINUS_SRC_ALPHA` for RGB and alpha; the old
    `SRC_ALPHA / ONE_MINUS_SRC_ALPHA` path incorrectly multiplied CEF's
    premultiplied RGB twice. MCEF remains opaque with blending disabled. The
    complete texture lookup/state/buffer/draw path is covered by one render
    lease `try/finally`, including the `textureId <= 0` return, and a successful
    Minecraft draw explicitly marks its native generation.

    Native diagnostics now expose current WGL registration state, balanced
    begin/end and lock/unlock counters, failures, published/new/repeated draws,
    producer drops, resize/context refresh counts and one-shot Minecraft
    context/interop/registration/lease/draw checkpoints. Ordinary resize only
    re-samples HGLRC/HDC; actual identity change performs the safe rebind and
    skips the transition frame. Hidden `WasHidden(true)` still suppresses
    BeginFrame, accelerated paint copies and GL leases. The optional runner
    evidence file never reports PASS without balanced invariants and a real
    first-draw marker.

    Runtime ownership is split conceptually into process-global CEF resources,
    retained browser/session/mailbox/bridge resources, and Screen attachment
    focus/input/drawing. At this historical checkpoint,
    `plans/direct-cef-distribution-plan.md` was design-only; checkpoint 15 below
    records the implemented Phase A discovery boundary. No downloader, updater,
    automatic install or production runtime archive was added here.

15. Direct CEF runtime distribution Phase A freezes schema v1 and the project
    requirement `cef-144.0.33-cb4715c` / ABI 1 / Windows x86_64. The NeoForge
    Direct backend discovers a prepared runtime from the explicit validated
    `mcwebui.directCef.runtimeDir` override or the standard instance location,
    with invalid override precedence and no MCEF fallback. It validates the
    manifest identity, safe relative paths, complete file tree, byte sizes and
    streaming SHA-256 before the native loader can run.

    The native loader now accepts only `ValidatedDirectCefRuntime`; the old
    static arbitrary-path `mcwebui.directCef.native` load was removed. Its
    process-global guard allows the same canonical root/identity and rejects a
    runtime switch. Mutable cache data is kept under the instance cache tree,
    outside the immutable runtime directory. The proof runner assembles a
    standard directory by default and retains an explicit override mode, both
    using the same manifest generator, validator and loader.

    Bounded NeoForge runs verified both `STANDARD` and `OVERRIDE`: each
    validated a 239-file prepared runtime, started the process-owned bundled
    page, completed one bridge handshake, and completed hidden prewarm with two
    accelerated generations. Lease/interop invariants were balanced with zero
    registration, lock or unlock failures. The runs did not perform a new
    visible first-draw/manual visual acceptance.

    Distribution status is **PHASE A IMPLEMENTED / PHASE B-C NOT
    IMPLEMENTED**. This means **MANUAL PREINSTALLED DIRECTORY SUPPORTED** only.
    Offline ZIP import, extraction/staging/atomic publication, download,
    updater, cleanup and release signing remain future work; do not describe
    them as available.

16. Direct CEF runtime distribution Phase B implements offline package import
    and a Minecraft setup screen. The frozen package format v1 is a ZIP with
    `runtime.json` at its root whose payload exactly matches the manifest
    `files[]` set. `DirectCefRuntimePackageImporter` reads the manifest first,
    reuses the Phase A identity/manifest gates, validates every entry path and
    exact entry set (no extras, no case duplicates, no zip bombs), extracts
    with per-file size caps and one-pass SHA-256 into a sibling staging
    directory, re-validates with the Phase A validator, publishes with
    `ATOMIC_MOVE` (same-filesystem fallback), and re-discovers through the
    standard path. A process-shared `FileChannel`/`FileLock` in
    `mcwebui/runtime/.locks/` serializes installers (`INSTALL_IN_PROGRESS`
    after a bounded wait); a valid existing runtime yields
    `ALREADY_INSTALLED`; a corrupt one is quarantined
    (`windows-x86_64.invalid-<uuid>`) and replaced with rollback; a runtime
    loaded by the current JVM returns `RUNTIME_IN_USE`. Cancellation cleans
    only its own staging.

    The F8 flow now opens `DirectCefRuntimeSetupScreen` (a plain Minecraft
    Screen, never the Web UI) when standard discovery fails for
    `browserBackend=direct-cef`: it shows the required CEF/Chromium identity,
    typed player-facing failure messages, the expected directory, a package
    path field and Import/Retry/Open Runtime Folder/Cancel. Import runs on a
    worker thread with immutable progress snapshots and returns to the client
    thread; success re-runs discovery and offers Continue. Import is disabled
    for reasons a package cannot fix (wrong platform/arch, ABI/schema,
    runtime in use). MCEF stays untouched, Forge 1.20.1 keeps building, and no
    runtime binaries enter the mod JAR.

    Historical checkpoint-16 status was **PHASE A-B IMPLEMENTED / PHASE C NOT
    IMPLEMENTED**; checkpoint 17 below supersedes only the Phase C core status:
    PREINSTALLED DIRECTORY SUPPORTED, OFFLINE RUNTIME PACKAGE IMPORT SUPPORTED,
    AUTOMATIC DOWNLOAD NOT IMPLEMENTED, AUTO UPDATE NOT IMPLEMENTED.
    Package/file SHA-256 proves integrity and exact artifact identity only, not
    publisher authenticity; the Phase C trust model (HTTPS metadata, release
    signing, signed manifest) is still to be decided. Evidence lives in
    `scripts/direct-cef-runtime/test-runtime-import.ps1` (package → fresh
    instance import → standard rediscovery → real NeoForge Direct startup with
    bundled page, bridge handshake and hidden prewarm, with no
    `mcwebui.directCef.runtimeDir` override) and
    `scripts/direct-cef-runtime/package-runtime.ps1` (deterministic packaging).
    Setup screen acceptance status: **READY FOR USER ACCEPTANCE** — the
    automated in-game import click-through has not been performed by a human
    in this checkpoint.

17. Phase B setup is hardened and Phase C now has a download foundation, but
    no official runtime release or production source exists yet. An invalid
    explicit `mcwebui.directCef.runtimeDir` remains authoritative: the native
    Minecraft setup screen enters a developer-override error state, displays
    the override and typed reason, and offers only correction-oriented actions.
    It never claims that downloading or importing into the standard directory
    repairs that override. Normal missing/corrupt standard-runtime failures
    retain offline Import, Retry, and Open Runtime Folder. Live progress reads
    one immutable job snapshot, and screen removal/close shares idempotent,
    cooperative executor disposal so stale completions cannot mutate a dead
    screen.

    `DirectCefRuntimeReleaseDescriptor` is project-owned metadata separate from
    package `runtime.json`. It pins descriptor version, artifact ID/revision,
    the Phase A runtime requirement, filename, exact size, SHA-256, and optional
    HTTPS URI. Any native bundle byte change requires a new official artifact
    revision and newly pinned size/hash even if the CEF version is unchanged;
    runtime-manifest schema v1 is not silently repurposed.
    `DirectCefRuntimeDownloader` is pure Java, streams HTTPS into a unique
    instance-local `.part`, enforces timeouts/redirect policy/size/hash/disk
    gates, supports cancellation, then calls the existing Phase B importer with
    the trusted package SHA. It never downloads during tick/init/prewarm.

    The generator can derive exact descriptor identity/size/hash from a valid
    Phase B ZIP and accepts an optional real HTTPS URL; it does not invent one.
    This checkpoint ships no configured descriptor and therefore the Setup
    Screen explicitly says automatic download is not configured while offline
    import continues to work. Status is **PHASE C CORE IMPLEMENTED /
    PRODUCTION SOURCE NOT CONFIGURED**, not “automatic download supported”.
    The next release gate is to publish the official runtime package, pin its
    real URL/size/SHA-256 in MCWebUI, and pass real HTTPS plus Minecraft Setup
    Screen acceptance with that exact artifact.

18. The Direct CEF production release pipeline is now prepared, but no release
    was created. `DirectCefRuntimeReleaseCatalog` is the local project-owned
    trust anchor at
    `META-INF/mcwebui/direct-cef-runtime-release.json`; it never fetches remote
    metadata. Resource absence means production source unconfigured. A present
    malformed/wrong-runtime/invalid source is a logged BUILD/RELEASE CONFIG
    ERROR, while the Setup Screen preserves offline import. The default Setup
    constructor uses this catalog instead of hard-coding a null descriptor.

    NeoForge accepts the explicit Gradle property
    `mcwebuiDirectCefReleaseDescriptor=<absolute path>`. The build validates the
    descriptor with the production Java parser/requirement gate before staging
    it at the fixed catalog path. No property keeps normal development builds
    descriptor-free; invalid input fails the build. Forge is unchanged. The
    descriptor schema v1 now has a backward-compatible optional
    `runtimePayloadSize`; new release candidates always emit it. Fresh-install
    advisory disk budgeting is ZIP + unpacked payload + 16 MiB, with the Phase
    B bounded writes remaining authoritative.

    `prepare-runtime-release.ps1` composes manifest validation, deterministic
    packaging, descriptor generation, checksums, and a source-traceable release
    report in an external directory. A real 239-file staging run produced a
    162,295,855-byte ZIP, 379,049,577 unpacked payload bytes, and SHA-256
    `C7555732A7B85DE2C079F7320184E455350DBD9986DCE8C1566B26D9854D7AD4`;
    repeated packaging was byte-identical. Its descriptor intentionally has no
    URL. No ZIP/runtime binaries were committed.

    `test-first-run-install.ps1` distinguishes `LOCAL_FIXTURE` from
    `REAL_RELEASE`. LOCAL_FIXTURE injects the local ZIP into the downloader,
    then passed package verification, Phase B import, Phase A standard
    discovery, real NeoForge bundled page, one Bridge handshake, and hidden
    prewarm. REAL_RELEASE is **NOT CONFIGURED**, not PASS. Setup Screen visual
    acceptance remains **READY FOR USER ACCEPTANCE**. Release ordering and the
    `DIRECT CEF DEVELOPER PREVIEW READY` prerequisites are documented in
    `plans/direct-cef-release-checklist.md`. Current status is **RELEASE
    PIPELINE READY / WAITING FOR OFFICIAL ASSET**; creating a tag/Release,
    uploading the ZIP, embedding the final descriptor, REAL_RELEASE acceptance,
    and publication all still require explicit user authorization.

### Near-term project direction

19. NeoForge Developer Preview deployment is hardened around an explicit
    backend-resolution gate. Persistent `browserBackend` supports MCEF,
    DIRECT_CEF and AUTO; the JVM property overrides config, while the historical
    default remains MCEF. MCEF presence is checked before its isolated bootstrap
    is invoked. Explicit choices never silently fall back. Missing dependency,
    unsupported platform, missing Direct runtime and initialization failure route
    to ordinary Minecraft Setup/recovery screens instead of deferred class-load
    crashes. `MCWebUIClient.backendStatus()` and `environment()` expose immutable
    implementation-neutral snapshots.

    MCEF no longer receives `--disable-web-security` or unused Widevine flags.
    `allowExternalNetwork` is documented honestly as intent metadata, not a
    Chromium firewall; current views still reject external network mode. Public
    sessions remain retained across ESC, same-app reopen reuses them, app switch
    closes the old bridge/backend, and pending Setup continuation is exact,
    latest-wins, and cleared on abandonment.

    A standalone `samples/neoforge-1.21.1-consumer` build now consumes only an
    isolated staged NeoForge binary coordinate and exercises the public API with
    `sample:control-panel`. `verifyConsumerSample` plus architecture and metadata
    audits prevent accidental project/sourceSet/backend/native coupling. No Maven
    publication or runtime release is performed. Full details live in
    `plans/developer-preview-deployment-plan.md`.

Before adding a large component library, finish the real transport and make the playground a **component showcase + integration laboratory**.

The showcase should demonstrate modern, attractive web UI controls while exercising actual framework behavior. Candidate sections include:

- buttons: primary/secondary/danger/disabled/loading;
- text input, password/search-like input, textarea;
- checkbox, radio, switch;
- select/dropdown;
- range slider;
- tabs/segmented control;
- modal/dialog;
- toast/notification;
- progress bar;
- cards/badges/tooltips;
- scroll area / list;
- RPC result panel;
- reactive Java state panel;
- runtime/paint/upload diagnostics;
- Chinese IME/clipboard test area.

Do not over-design a permanent UI design system yet. The showcase should first prove the runtime, transport, input, styling, responsiveness, and lifecycle.

### Long-term rule

Do not automatically implement Forge just to achieve symmetry if the user currently wants to focus on NeoForge. Instead make sure every new common/frontend abstraction remains portable, and record any NeoForge-only API behind target/backend ports. Add Forge when the user chooses to validate that target or when a design decision genuinely requires cross-target proof.

When a major milestone finishes, update this handoff file's **Current checkpoint** so a future conversation can resume quickly.

### Current checkpoint 20: Runtime R1 inputs frozen

The exact external Direct CEF Runtime R1 was clean-built from source commit
`740958afd63183e28e5b4d8168178ab7fb728d19`, assembled with CEF `LICENSE.txt`
and Chromium `CREDITS.html`, manifested, independently repacked, and exercised
through the exact LOCAL_FIXTURE download/import/discovery path. Both repacks are
byte-identical at SHA-256
`6B9DE0EF90869DB7C5966A6307516A886EB15B1AC6ED14BDBFD987DB912D4D6B`.
The 241-file ZIP is named
`mcwebui-direct-cef-runtime-cef-144.0.33-cb4715c-windows-x86_64-1.zip`, is
164216473 compressed bytes and 394723009 unpacked payload bytes. Full immutable
details are in `plans/direct-cef-runtime-r1-release.md`.

This checkpoint is **FROZEN / NOT PUBLISHED**. Production URL is UNCONFIGURED;
GitHub Release/tag/asset do not exist; REAL_RELEASE and official Setup acceptance
are not run. Do not rebuild or substitute the ZIP. The next action requires
explicit authorization to create `direct-cef-runtime-r1` and upload the exact
frozen artifact.

Any official Runtime R1 upload must first pass
`scripts/direct-cef-runtime/verify-runtime-release-inputs.ps1` against the exact
frozen ZIP and tracked `plans/direct-cef-runtime-r1-lock.json`; verifier failure
means release STOP, not rebuild-and-retry with different bytes.

The guarded operator is
`scripts/direct-cef-runtime/publish-runtime-r1.ps1`. With no `-Publish` it is
always a dry run and performs no remote mutation; the real dry run passed on
operator commit `11a865a0e98720e0aac078e35ca6e2e8422e372f`. Its `-Publish`
branch is implemented but remains unauthorized and unexecuted. Never bypass it
with an ad-hoc `gh release create`, and never run `-Publish` without explicit
user authorization.

### Current checkpoint 19: Direct compatibility and source candidate

Direct CEF now has two pre-runtime gates: a pure Windows/x86_64 static check and
a render-thread WGL capability probe. The probe requires NV_DX_interop,
NV_DX_interop2, and the exact entry points used by the runtime, but vendor
strings are diagnostics only. AUTO keeps MCEF precedence; unsupported or
unprobed graphics never opens runtime Setup or hidden prewarm. A public immutable
NeoForge compatibility snapshot exposes the result without leaking LWJGL/CEF.

The real host run passed on an NVIDIA RTX 5060 Ti with bridge handshake,
accelerated mailbox generations, one registered interop slot, balanced lock/
unlock, and zero failures. AMD/Intel remain not verified and there is no CPU
fallback. The source-only `0.1.0-preview.1` candidate gate propagates the version
through the independent consumer proof, rejects the default SNAPSHOT, audits the
JAR, and records an exact source SHA/checksum. Production runtime source remains
unconfigured and publication remains blocked pending explicit authorization.
