# Developer Preview deployment hardening

## Status

This checkpoint makes the NeoForge 1.21.1 Developer Preview consumable and
recoverable without changing the browser renderer, Direct CEF mailbox, runtime
package format, or public WebApp registration model. The historical default is
still MCEF. No runtime archive, release descriptor, tag, upload, or publication
is created here.

## Backend selection contract

The persisted client option `browserBackend` accepts `MCEF`, `DIRECT_CEF`, and
`AUTO`. For developer and CI runs, `-Dmcwebui.browserBackend=...` has higher
priority than the config. Selection is resolved after the client config has
loaded and before either backend is bootstrapped.

- Explicit MCEF requires the optional `mcef` mod. Missing MCEF produces a plain
  Minecraft recovery screen; it never falls back to Direct CEF.
- Explicit Direct CEF is Windows-only and never falls back to MCEF. If the MCEF
  mod is already loaded, Direct mode refuses to start and asks for a restart
  without MCEF; this preserves the one-CEF-process invariant. A missing or
  invalid Direct runtime continues through the existing runtime Setup screen.
- AUTO conservatively chooses installed MCEF first, then the eligible Direct CEF
  setup path on Windows. If neither is eligible, it opens the recovery screen.
- MCEF remains an optional dependency in `neoforge.mods.toml`. The entrypoint
  checks `ModList` before invoking the isolated MCEF bootstrap class, so Direct
  mode does not initialize or require MCEF/JCEF 116.

`MCWebUIClient.backendStatus()` exposes an immutable preference/resolution/
availability/failure snapshot. `MCWebUIClient.environment()` exposes immutable
target, loader, Minecraft, Java, OS and MCEF-presence facts without leaking CEF,
JNI, renderer, or loader implementation objects.

## Recovery and retained lifecycle

`MCWebUIClient.createScreen` must run on the Minecraft client thread. It returns
one of three ordinary `Screen` outcomes: the retained WebScreen, Direct CEF
runtime Setup, or backend-unavailable recovery UI. `open` schedules that decision
on the client thread.

ESC hides and retains the active public session. Reopening the same app reuses
that session; opening a different app closes the previous bridge, subscriptions,
page server and backend before creating the replacement. Framework shutdown
closes retained demo and public sessions. While Setup owns the UI, the pending
slot is last-request-wins; Continue consumes exactly that app and Close/ESC/
Cancel clears it.

## Browser security boundary

MCEF no longer starts Chromium with `--disable-web-security` or an unused
`--enable-widevine-cdm`. The showcase retains only its independent autoplay
policy. The `mcui` scheme remains secure/CORS-enabled and does not request a CSP
bypass.

`WebPermissionPolicy.allowExternalNetwork` is application intent metadata, not
a Chromium network sandbox. Current `WebViewConfig` rejects external-network
mode. The Developer Preview therefore makes no claim that this flag alone
intercepts or blocks arbitrary browser requests.

## Binary consumer gate

`samples/neoforge-1.21.1-consumer` is an independent Gradle build. The root gate
stages the final NeoForge JAR and minimal Maven metadata into an isolated local
repository, then builds the sample against that binary coordinate. The sample
must not use a composite build, project dependency, source set, classes output,
MCEF, CEF, JNI, `target.*`, or `nativecef.*` imports.

The sample registers `sample:control-panel`, serves packaged HTML/JS/CSS, opens
it from F9 through the public NeoForge facade, and exposes only public bridge
methods/events (`sample.echo`, `sample.ready`). This is a compile/package proof;
it is not a public Maven publication.

## Release gate

Required checks are frontend typecheck/build, all target tests/builds,
architecture check, `verifyConsumerSample`, artifact metadata inspection, and
JAR native/runtime leakage audit. Direct CEF release A/B/C verification remains
unchanged. This checkpoint can be called deployment-ready only after all gates
pass from a clean staging directory.
