# NeoForge Developer Preview WebScreen API

## Checkpoint

This checkpoint exposes API version 1 for NeoForge 1.21.1. It is a Developer
Preview, not a stable binary compatibility promise. The Direct CEF release and
download pipeline is unchanged: no descriptor, runtime archive, tag, or upload
is created by this work.

## Public contract

- `dev.qingmo.mcwebui.api` is Java 17, loader-independent, and contains no
  Minecraft, NeoForge, MCEF, CEF, JNI, OpenGL, or D3D types.
- `WebAppId` is a canonical lower-case `namespace:path`; path segments use
  `[a-z0-9._-]+` and traversal/URL syntax is rejected.
- `WebAppDefinition` is immutable and owns one resource provider, entry path,
  bridge dispatcher, permission policy, and `WebScreenOptions`.
- `WebAppRegistry` rejects last-writer-wins. Re-registering the identical
  definition object is idempotent; a different definition with the same ID is
  an error.
- `WebScreenOptions` describes only user-visible semantics: pause, ESC close,
  transparent composition, and GUI/framebuffer viewport policy.
- `dev.qingmo.mcwebui.api.neoforge.MCWebUIClient` exposes `register`,
  `createScreen`, and `open`. `open` schedules to the client thread;
  `createScreen` rejects calls from other threads.

## Resource and trust mapping

A registered `namespace:path` app resolves a relative resource as
`/<app-path>/<relative>` through its own provider. A conventional
`ClasspathWebResourceProvider(loader, "web")` therefore loads from
`web/<namespace>/<app-path>/` inside the consumer JAR.

MCEF maps each registered identity to a deterministic, SHA-256-qualified
`mcui://app-<digest>.mcwebui/` origin. Direct CEF starts a per-app IPv4-loopback
server on an ephemeral port with a fresh 192-bit capability path. It accepts
GET/HEAD only, exposes no directory listing or filesystem path, rejects encoded
traversal, and delegates bytes/MIME/status to the same provider used by MCEF.
Unknown hosts and paths do not inherit another app's provider.

Bridge dispatchers and permission policies are per definition and become a new
`WebBridge` for each session. Closing or switching a registered app closes the
old bridge/subscriptions/backend before creating the next session. MCEF's
navigation handler and Direct CEF's exact capability URL/epoch reset revoke the
bridge on external navigation.

## Runtime setup continuation

When Direct CEF is explicitly selected and validation fails, the API stores the
specific requested `WebAppId` and opens the existing Setup screen. A successful
Continue schedules that same app again. It does not substitute the playground
and does not silently select MCEF.

## Verification

- Common tests cover ID validation/canonicalization, duplicate registration,
  immutable options, resource mapping, empty permission sets, and dispatcher
  isolation.
- NeoForge tests cover provider-equivalent MCEF/Direct loading, loopback token
  isolation, GET/HEAD, traversal and unknown-origin rejection, existing backend
  selection, Direct bridge handshake/state/RPC and navigation reset behavior.
- The tiny test consumer at `web/consumer/sample/` proves app identity and
  packaged HTML/JS/CSS lookup without adding a production demo asset.

The native Direct integration test remains opt-in because it requires the exact
validated Windows runtime. When enabled it exercises the existing accelerated
runtime, real bridge handshake, and renderable-generation gate using
the provider-backed loopback page. This API checkpoint does not alter the
mailbox, Present, WGL/D3D interop, frame pacing, downloader, or release catalog.

On the development host, the opt-in CEF 144 integration passed with a registered
provider page, `bridgeBootstrapInstalled=true`, at least one completed browser
handshake, and a non-zero published accelerated generation. The separate
`DirectBridgeHost` tests cover RPC/state delivery and mandatory re-handshake
after navigation; the headless integration does not claim a Minecraft draw.

## Preview limitations

- One WebScreen is active at a time. Multiple apps may be registered.
- Direct CEF still has a process-global lifecycle; broad hardware/fullscreen
  acceptance and production runtime distribution remain separate gates.
- Forge 1.20.1 receives the common Java API classes but no new public screen
  facade in this checkpoint.

## Deployment hardening checkpoint

The subsequent deployment checkpoint preserves API version 1 while adding a
persisted NeoForge backend preference (`MCEF`, `DIRECT_CEF`, `AUTO`), explicit
availability/failure UI, immutable public backend/environment diagnostics, and
an isolated binary-consumer build. The historical default remains MCEF. Explicit
selection never silently falls back; AUTO is the only fallback policy.

MCEF is checked through the loader before its isolated bootstrap class is used.
Its Chromium command line no longer disables web security or enables unused
Widevine. Direct CEF runtime absence continues to the native Setup screen, whose
pending registered-app continuation is latest-wins and is cleared by Close,
Cancel, or ESC. See `plans/developer-preview-deployment-plan.md` for the exact
deployment and security boundaries.
