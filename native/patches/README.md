# GAME_SYNC proof patches

These patches are source-only proof artifacts. They are generated from exact
upstream checkouts and do not vendor JCEF/MCEF sources, CEF SDK files, or
native binaries.

## JCEF

Apply `jcef-a78e832-game-sync.patch` to `java-cef` commit `a78e832` after
checking it with `git apply --check`. The Java API defaults to disabled
external pacing; only the proof constructor sets
`CefWindowInfo.external_begin_frame_enabled`. Calls are dispatched to CEF's
UI thread and invoke `CefBrowserHost::SendExternalBeginFrame`. The native
capability marker makes a patched-Java/stock-native mismatch fail immediately.

## MCEF

Apply `mcef-2.1.6-game-sync.patch` to MCEF `2.1.6-1.21.1` after applying the
JCEF patch. Existing constructors and `MCEF.createBrowser` overloads retain
stock behavior (`false`); proof callers opt in with the boolean overload.
The proof-only native directory override bypasses MCEF's downloader, so it
cannot replace the matched patched JCEF library during startup.

The stock MCWebUI artifact intentionally remains on the existing MCEF binary
until a patched JCEF/MCEF pair is built and distributed together.
