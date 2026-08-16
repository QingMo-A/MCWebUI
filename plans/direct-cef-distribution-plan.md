# Direct CEF runtime distribution plan

Status: **PHASE A-B-C IMPLEMENTED / RUNTIME R1 PUBLISHED / MOD NOT PUBLISHED** (2026-08-16).

This plan defines how MCWebUI distributes and locates the external Direct CEF runtime. Phase A implements trusted discovery and validation, Phase B adds safe offline import, and Phase C provides the pinned HTTPS downloader/install flow. Runtime R1 and its project-owned descriptor are now configured for NeoForge; the MCWebUI mod itself is not published.

## Phase A implementation checkpoint

The frozen runtime requirement is schema v1 / runtime ABI 1 / `cef-144.0.33-cb4715c`, with CEF `144.0.33`, Chromium `144.0.7559.259`, platform `windows`, and architecture `x86_64`. The project-owned requirement is compared against `runtime.json`; the manifest does not define what MCWebUI wants.

The schema records four entrypoints (`native`, `helper`, `cef`, and `chromeElf`) plus a complete, deterministically ordered file list. Every listed file has an exact byte size and SHA-256. Validation occurs before native loading and rejects unsafe relative paths, case-insensitive duplicates, reparse/symbolic links, missing or unexpected files, identity mismatches, size mismatches, and hash mismatches with typed failure reasons.

Java's Windows filesystem provider does not expose every reparse subtype uniformly.
Phase A therefore combines explicit symbolic-link/reparse probes with
`NOFOLLOW_LINKS` checks and real-path containment. Escapes outside the selected
runtime root are rejected; Phase B archive staging must harden this boundary
again rather than assuming every Windows reparse subtype was fully classified.

Phase A supports these two preinstalled-directory sources, in this order:

1. explicit validated developer override: `mcwebui.directCef.runtimeDir`;
2. standard instance location: `<instance>/mcwebui/runtime/cef/cef-144.0.33-cb4715c/windows-x86_64`.

An invalid explicit override is authoritative and does not fall through to the standard directory. Mutable CEF data remains separate at `<instance>/mcwebui/cache/cef/cef-144.0.33-cb4715c` unless an explicit cache override is supplied. The validated object provides the resolved DLL/helper entrypoints to the process-global native loader; the old class-initializer path that loaded an arbitrary `mcwebui.directCef.native` string has been removed. A JVM may reuse the same validated root and identity, but it rejects switching roots or identities after selection/loading.

`scripts/direct-cef-runtime/generate-runtime-manifest.ps1` creates the deterministic Phase A manifest for a prepared runtime directory. It is a consistency tool, not a signer: SHA-256 detects mismatched content but does not establish a trusted publisher. The proof runner assembles a temporary standard layout by default and also has an `Override` mode; both pass through the same validator and loader.

**MANUAL PREINSTALLED DIRECTORY, OFFLINE IMPORT, AND PINNED RUNTIME R1 DOWNLOAD ARE SUPPORTED.**

## Phase B implementation checkpoint

Phase B implements offline runtime package import with the same validation boundary as Phase A; it never writes a second validation dialect.

### Package format v1 (frozen)

```text
mcwebui-direct-cef-runtime-<runtime-id>-<platform>-<arch>.zip
└─ runtime.json                      (must be at the ZIP root, exactly one)
   mcwebui-direct-cef.dll
   mcwebui-cef-helper.exe
   libcef.dll
   chrome_elf.dll
   ...
   locales/...
```

The ZIP payload must be exactly `runtime.json` + the complete `files[]` set from
that manifest, plus only safe directory entries that are ancestors of listed
files. No top-level wrapper folder, no extra files, no symlinks. The package
filename is never trusted; identity comes from comparing the packaged
`runtime.json` (schema, runtimeId, ABI, CEF/Chromium version, platform, arch)
against the project-owned requirement before any large extraction happens.

### Importer (`DirectCefRuntimePackageImporter`)

- reads the ZIP with `java.util.zip.ZipFile` and parses `runtime.json` first
  (bounded to 16 MiB), then reuses the Phase A identity and manifest gates;
- validates every entry path (`/`-relative only: rejects absolute paths,
  drive qualifiers, UNC/backslash forms, `.`, `..`, empty components, NUL) and
  rejects case-insensitive duplicates including a second `runtime.json`;
- requires the entry set to match the manifest exactly: no missing files, no
  extra files, no empty directory entries;
- enforces declared-size equality plus a hard streaming write bound per file
  (manifest size; stream over/under-run fails) and absolute per-file (1 GiB)
  and total (4 GiB) uncompressed bounds — `ZipEntry.getSize()` alone is never
  the only safety boundary;
- extracts with buffer streaming while computing SHA-256 in the same pass, then
  checks actual size and hash against the manifest;
- never restores symlinks/links/reparse metadata — only plain files and
  directories are created;
- stages as a sibling of the final directory (`windows-x86_64.installing-<uuid>`),
  never inside `TEMP` across volumes, never into the final path;
- calls the Phase A validator on the staging tree, publishes, validates the
  published tree again, then re-runs `DirectCefRuntimeDiscovery` from the
  standard directory. The importer only installs; it never creates a browser.
- supports an optional whole-package SHA-256: when supplied it is verified
  first; without it the internal manifest/file hashes still prove consistency.
  Neither form establishes publisher authenticity (Phase C trust model).

### Concurrency and safety

- process-shared lock via `FileChannel`/`FileLock` in the stable
  `mcwebui/runtime/.locks/` directory (never inside a final runtime), keyed by
  runtime identity + platform + arch; `OverlappingFileLockException` and
  Windows cross-process lock failures are treated as busy with a bounded wait,
  then `INSTALL_IN_PROGRESS`;
- under the lock the standard runtime is re-discovered: a valid one returns
  `ALREADY_INSTALLED` without touching anything;
- a corrupt existing runtime is quarantined
  (`windows-x86_64.invalid-<uuid>`) only after the lock is held and the target
  is not loaded by this process (`RUNTIME_IN_USE` otherwise), then replaced;
- publish prefers `Files.move(..., ATOMIC_MOVE)` with a same-filesystem normal
  move fallback; `REPLACE_EXISTING` is never used; a failed publish rolls the
  quarantined runtime back and the error names staging/quarantine/final paths;
- cooperative cancellation is checked at file boundaries and inside large
  streams; cancellation removes only this run's staging and never a final
  runtime.

### Setup screen (`DirectCefRuntimeSetupScreen`)

Only `browserBackend=direct-cef` with failed discovery opens it. It is a plain
Minecraft Screen (never the Web UI, avoiding a CEF-missing bootstrap cycle):
shows the required identity, a typed player-facing status message, the expected
directory, a package path field, and Import/Retry/Open Runtime Folder/Cancel
buttons. Import runs on a worker thread publishing immutable progress snapshots
(VALIDATING_PACKAGE, WAITING_FOR_LOCK, EXTRACTING, VALIDATING_RUNTIME,
PUBLISHING, COMPLETE, FAILED, CANCELLED); completion returns to the client
thread, re-runs discovery, and offers Continue. Import is disabled for reasons
a package cannot fix (wrong platform/arch, ABI/schema mismatch, runtime in use).
No AWT/Swing file picker is introduced; the first version accepts a pasted ZIP
path.

### Support level (after Phase B)

| Path | Status |
|---|---|
| PREINSTALLED DIRECTORY | SUPPORTED |
| OFFLINE RUNTIME PACKAGE IMPORT | SUPPORTED |
| AUTOMATIC DOWNLOAD | RUNTIME R1 CONFIGURED / REAL_RELEASE PASS |
| AUTO UPDATE | NOT IMPLEMENTED |

## Phase C core checkpoint

Phase C introduces a project-owned release descriptor and a pure-Java download
pipeline without configuring a production source. The descriptor pins its own
version, artifact ID and revision, the complete Phase A runtime requirement,
package filename, exact byte size, optional unpacked payload size, SHA-256, and
optional HTTPS URI. It does not
trust the package's internal `runtime.json` to select the source or expected
outer package hash. `runtime.json` remains the Phase A/B payload identity and
file-integrity boundary; the release descriptor is the MCWebUI release pin.

The first descriptor schema is version 1. `artifactRevision` is part of the
official distribution identity: rebuilding or changing the native DLL/helper
or any other runtime payload requires a new artifact revision and newly pinned
size/hash, even when the CEF and Chromium versions are unchanged. This rule
avoids silently replacing bytes behind an existing official identity and does
not change the frozen runtime-manifest schema v1.

`DirectCefRuntimeDownloader` uses JDK `HttpClient`, explicit connection/request
timeouts, HTTPS-only sources and redirects, exact `Content-Length`/stream size
bounds, streaming SHA-256, a best-effort fresh-install disk-space gate for the
downloaded ZIP + unpacked staging + 16 MiB margin, throttled immutable
progress, and cooperative cancellation. Each attempt owns a fresh
`<instance>/mcwebui/runtime/.downloads/<artifact>.<uuid>.part`; failure or
cancellation removes only that file. A verified package is passed to the Phase
B importer with the descriptor's expected package SHA-256, so extraction,
locking, validation, repair/rollback, atomic publish, and Phase A rediscovery
remain one implementation. There is no resume path and no download occurs from
tick, initialization, or hidden prewarm; the first product flow requires an
explicit Setup Screen click.

The Setup Screen now treats an invalid explicit `runtimeDir` override as an
authoritative developer error. It shows the override and typed reason and asks
the developer to fix or remove the JVM property; installing into the standard
directory is not offered as a false repair. Without an override, offline import
remains available. The default constructor now obtains its project-owned pin
from `DirectCefRuntimeReleaseCatalog` at
`META-INF/mcwebui/direct-cef-runtime-release.json`. A missing resource is the
supported development/unconfigured state. A present malformed, wrong-runtime,
or unsafe descriptor is logged as a BUILD/RELEASE CONFIG ERROR; automatic
offline import remains available. NeoForge now bundles the validated Runtime R1
descriptor; Forge does not.

`scripts/direct-cef-runtime/generate-release-descriptor.ps1` accepts a complete
Phase B ZIP plus an explicit artifact revision, revalidates the archive against
its `runtime.json`, and emits exact size/hash/identity metadata. The URL is
optional; omitting it produces unconfigured metadata rather than a fake source.
This generator is release tooling, not a signer and not permission to publish
an artifact.

### Release pipeline checkpoint

`scripts/direct-cef-runtime/prepare-runtime-release.ps1` composes the existing
manifest, deterministic package, and descriptor generators into one external
release-staging gate. It produces the runtime ZIP, an unconfigured descriptor
candidate, `checksums.txt`, and `release-report.json`. The report records the
runtime/CEF/Chromium identity, four entrypoints, artifact revision, compressed
and unpacked sizes, SHA-256, file count, and source Git SHA. Its report-only UTC
timestamp never enters the deterministic ZIP.

NeoForge now defaults to the project-owned Runtime R1 descriptor at
`gradle/direct-cef-runtime-r1.release.json`. The optional
`-PmcwebuiDirectCefReleaseDescriptor=<absolute-path>` remains an explicit build
override. Both paths use the production Java parser/requirement checks before
embedding the fixed catalog resource. Invalid descriptors fail the build;
Forge carries no Direct backend release metadata.

`scripts/direct-cef-runtime/test-first-run-install.ps1` has two explicit modes.
`LOCAL_FIXTURE` injects the locally prepared ZIP as a downloader source without
inventing or persisting a production URL, then requires package verification,
Phase B import, Phase A standard discovery, bundled page, Bridge handshake, and
hidden prewarm. `REAL_RELEASE` requires an actual configured HTTPS descriptor;
without one it reports `NOT CONFIGURED`, never PASS. Release ordering and the
Developer Preview gate are frozen in `plans/direct-cef-release-checklist.md`.

### Historical Phase C core verification (2026-08-14; superseded by Runtime R1 publication)

- release catalog tests cover absent, valid, explicitly unconfigured,
  malformed, wrong-runtime, invalid URL/size/SHA/schema, and strict build-time
  configured-source validation;
- descriptor embedding gates were exercised with no property (resource
  absent), a valid absolute descriptor (fixed resource packaged), and an
  invalid descriptor (build fails before packaging);
- a real 239-file CEF 144 staging run produced a 162,295,855-byte deterministic
  ZIP with SHA-256
  `C7555732A7B85DE2C079F7320184E455350DBD9986DCE8C1566B26D9854D7AD4` and
  379,049,577 unpacked payload bytes; its candidate and report state
  `UNCONFIGURED`, and no binary/staging output is committed;
- the LOCAL_FIXTURE downloader/import/discovery gate passed in a fresh
  standard instance, followed by a real NeoForge bundled-page start, one
  Bridge handshake, and hidden accelerated prewarm. The visible first draw
  remains a separate user action and was not claimed by this headless gate;
- at that historical checkpoint `REAL_RELEASE` reported `NOT CONFIGURED` and no
  remote release existed; the current Runtime R1 publication and acceptance
  results above supersede only that release-state statement;

- deterministic no-network tests cover descriptor parsing/missing/malformed and
  unsafe fields; exact success/import/rediscovery; short and oversized bodies;
  content/hash mismatch; cancellation and owned `.part` cleanup; timeout/HTTP,
  DNS/connect/TLS/write/disk classifications; HTTPS redirect acceptance and
  downgrade/file/fragment rejection; and live download/install progress;
- Setup model/lifecycle tests cover invalid override precedence even beside a
  valid standard runtime, real offline import followed by Continue availability,
  normal completion, cancellation, idempotent disposal, executor shutdown,
  stale UI callback rejection, and an intermediate EXTRACTING snapshot;
- the real Phase B chain repackaged a 237-file CEF144 runtime, imported it into
  a fresh standard instance, then reached the bundled page, one Java bridge
  handshake, and hidden accelerated prewarm in NeoForge with no runtimeDir
  override;
- native CEF lifecycle smoke exited 0 with `ready=true`; local frontend
  typecheck/build plus common, Forge 1.20.1, and NeoForge 1.21.1 tests/builds
  passed; two built target JARs contained no Direct CEF DLL/EXE/PAK/ZIP payload;
- Runtime R1 is published at the pinned GitHub HTTPS asset. REAL_RELEASE from a
  fresh instance passed exact download, SHA, Phase B import and Phase A
  discovery; the installed runtime then passed NeoForge bundled-page, Bridge,
  accelerated interop and hidden-prewarm markers. The mod was not published.

### Phase B verification (2026-08-14)

- unit matrix covers: valid install + standard rediscovery, ALREADY_INSTALLED,
  missing/duplicate `runtime.json`, wrong schema/runtimeId/ABI/platform/arch,
  missing manifest file, extra entry, unnecessary directory entry, unsafe
  paths (`../`, absolute, backslash, drive-qualified, `//`, `./`), case
  duplicates, declared-size mismatch, truncated entry/file, content hash
  mismatch, zip-bomb size bounds, optional package hash, cancellation,
  mid-extraction failure, lock contention, concurrent imports, corrupt-runtime
  repair, publish-failure rollback, loaded-runtime protection, deterministic
  packaging, and setup message coverage;
- the deterministic package builder repackages the same tree byte-identically
  (fixed timestamps, sorted entries, fixed separators; byte-identity holds on
  the same .NET runtime, content/order always);
- the real proof (`scripts/direct-cef-runtime/test-runtime-import.ps1`)
  packages the prepared 239-file runtime, imports it into a fresh instance via
  the Java importer, then starts a real NeoForge Direct client from the
  standard directory with **no** `mcwebui.directCef.runtimeDir` override:
  bundled page + bridge handshake + hidden prewarm must pass.

**Integrity vs authenticity:** file SHA-256 and an optional package SHA-256
prove exact artifact identity and content consistency only. A package hash that
comes from the package itself cannot prove publisher authenticity; Phase C must
define a trusted HTTPS metadata / release signing / signed manifest model.
Never describe "SHA-256 means the package is trusted".

### Phase A verification (2026-08-14)

Repeated generation over the fixed CEF 144 build directory was byte-for-byte
deterministic. The runner excludes the standalone smoke executable and produced
a 239-file proof manifest. Both runner modes reached a real NeoForge Direct
startup through the new boundary:

| Source | Selected directory kind | Manifest | Bundled page | Bridge | Hidden prewarm |
|---|---|---:|---:|---:|---:|
| `STANDARD` | standard instance tree | 239 files validated | started | 1 handshake | completed, 2 accelerated generations |
| `OVERRIDE` | non-standard explicit directory | 239 files validated | started | 1 handshake | completed, 2 accelerated generations |

Both runs reported balanced render leases and interop locks with zero
registration/lock/unlock failures. They intentionally stopped after the hidden
prewarm gate; the evidence collector therefore kept its separate visible
first-draw/manual-acceptance state as `USER_ACTION_REQUIRED`. That does not
invalidate the discovery/validation/load/prewarm result and is not presented as
a new Minecraft visual acceptance run.

The primary product goal is to keep the MCWebUI mod JAR small while still making first-run setup reliable for normal players, offline users, modpack authors, and developers.

## Product principles

1. The Direct CEF runtime is a shared MCWebUI platform dependency, not a dependency that every consuming mod bundles separately.
2. The MCWebUI mod JAR must not embed the full CEF runtime, `libcef.dll`, helper executables, PAK files, locales, or SDK files by default.
3. Automatic installation is the preferred normal-player path, but it must never be the only supported path.
4. Manual/offline installation is a first-class supported workflow, not an undocumented escape hatch.
5. A runtime is considered usable only after identity, architecture, version and integrity validation succeeds.
6. A failed Direct CEF installation must not silently run an unknown or partially installed runtime.
7. Explicit `direct-cef` selection must preserve the existing no-silent-fallback rule unless a future user-facing policy explicitly changes that contract.

## Expected storage layout

Phase A freezes the standard Windows x86_64 directory layout as:

```text
.minecraft/
└─ mcwebui/
   └─ runtime/
      └─ cef/
         └─ <runtime-id>/
            └─ windows-x86_64/
               ├─ runtime.json
               ├─ mcwebui-direct-cef.dll
               ├─ mcwebui-cef-helper.exe
               ├─ libcef.dll
               ├─ chrome_elf.dll
               ├─ icudtl.dat
               ├─ resources.pak
               ├─ chrome_100_percent.pak
               ├─ chrome_200_percent.pak
               ├─ v8_context_snapshot.bin
               └─ locales/
```

The runtime location should normally be scoped to the Minecraft instance so that launchers, modpacks and portable instances remain self-contained. A future advanced override may point to a shared cache elsewhere, but MCWebUI must not require a system-global install.

Do not install into `Program Files`, `System32`, the Windows registry, or the global `PATH`.

## Runtime identity

Each supported runtime has a stable identity. Schema v1 is:

```json
{
  "schemaVersion": 1,
  "runtimeId": "cef-144.0.33-cb4715c",
  "mcwebuiRuntimeAbi": 1,
  "cefVersion": "144.0.33",
  "chromiumVersion": "144.0.7559.259",
  "platform": "windows",
  "arch": "x86_64",
  "entrypoints": {
    "native": "mcwebui-direct-cef.dll",
    "helper": "mcwebui-cef-helper.exe",
    "cef": "libcef.dll",
    "chromeElf": "chrome_elf.dll"
  },
  "files": [
    {
      "path": "libcef.dll",
      "size": 0,
      "sha256": "..."
    }
  ]
}
```

Future schema versions may extend this model, but schema v1 requires:

- MCWebUI runtime ABI/version compatibility;
- exact CEF/runtime identity;
- platform and architecture;
- complete required file list;
- expected file size;
- SHA-256 for every distributed file that participates in runtime integrity.

MCWebUI should reject a runtime that is missing required files, targets the wrong architecture/platform, has an incompatible runtime ABI, or fails integrity checks.

## First-run discovery flow

When Direct CEF is needed, the runtime manager should conceptually execute:

```text
Direct CEF requested
        ↓
Resolve required runtime identity
        ↓
Check configured/manual runtime override
        ↓
Check normal MCWebUI runtime directory
        ↓
Validate runtime manifest + required files + SHA-256
        ↓
┌─────────────────────────────┐
│ valid runtime found         │ → use it
└─────────────────────────────┘
        ↓ none valid
Offer installation paths
```

Runtime discovery must be separate from runtime loading. No native DLL should be loaded until validation has completed.

## Supported installation paths

MCWebUI should ultimately support three official installation modes.

### 1. Automatic installation

This is the normal player flow.

Example UX:

```text
MCWebUI needs a browser runtime to use Direct CEF.

Runtime: CEF 144.0.33 / Windows x64
Download size: <known package size>

[Install] [Cancel]
```

The implementation should download to a temporary staging directory, validate the package and files, then atomically publish the completed runtime into its final location.

Never download directly into the final live runtime directory.

Conceptual flow:

```text
download.tmp/
    ↓
complete download
    ↓
manifest validation
    ↓
SHA-256 validation
    ↓
archive/path safety validation
    ↓
extract into staging
    ↓
validate extracted runtime again
    ↓
atomic publish / rename
    ↓
runtime becomes discoverable
```

Interrupted or failed downloads must not leave a directory that later looks like a valid runtime.

### 2. Import a runtime ZIP/package

This is the preferred manual/offline workflow.

The player may download the runtime package using a browser, another computer, a mirror, a modpack installer, or removable storage, then choose:

```text
[Import Runtime Package]
```

MCWebUI should accept only a package containing a supported manifest/layout, validate it exactly like an automatically downloaded package, install it through the same staging/atomic-publish path, and never trust a ZIP solely because its filename looks correct.

Expected use cases:

- automatic download server unreachable;
- GitHub/CDN unavailable in the user's region;
- offline computer;
- modpack distributor provides the package separately;
- user transfers the runtime with a USB drive;
- enterprise/school network blocks automatic downloads.

A manually imported package must be functionally equivalent to one obtained by the automatic installer.

### 3. Advanced existing-directory override

Developers and advanced pack maintainers may point MCWebUI at an already prepared runtime directory, for example through a system property/config option.

Conceptually:

```text
-Dmcwebui.runtimeDir=D:\MCWebUI\cef144
```

This path must still undergo compatibility and integrity validation unless an explicitly development-only unsafe mode exists.

This is not intended as the normal player UX.

## Download failure UX

Automatic installation failure must never end with only a generic stack trace or an endless retry loop.

A future UI should expose an error state equivalent to:

```text
MCWebUI browser runtime installation failed.

Reason:
Unable to reach the configured download source.

[Retry]
[Import Runtime Package]
[Open Runtime Folder]
[Copy Error Details]
```

Where appropriate, also provide a human-readable expected runtime identity so users know exactly what offline package is needed.

The error UI must distinguish at least:

- network/download failure;
- HTTP/source failure;
- unsupported platform/architecture;
- invalid manifest;
- hash mismatch/corrupt package;
- insufficient disk space;
- access/permission failure;
- runtime directory already in use/locked;
- incompatible runtime version.

## Offline installation guarantee

MCWebUI Direct CEF should remain installable on a machine with no internet access.

Supported conceptual workflow:

```text
Computer A (online)
↓
download official MCWebUI runtime package
↓
USB / LAN / external storage
↓
Computer B (offline)
↓
Import Runtime Package
↓
validate
↓
install
↓
Direct CEF works without network access
```

Automatic downloading must therefore remain an installation convenience, not a runtime requirement.

After installation, Direct CEF must not require contacting the distribution server merely to start a valid cached runtime.

## Shared runtime semantics

Multiple mods that depend on MCWebUI must reuse the same compatible MCWebUI runtime installation.

This is explicitly forbidden as a product architecture:

```text
EconomySystem.jar -> private CEF copy
QuestMod.jar      -> private CEF copy
AuctionMod.jar    -> private CEF copy
```

The desired architecture is:

```text
EconomySystem ─┐
QuestMod       ├─> MCWebUI runtime manager -> one compatible CEF runtime
AuctionMod     ┘
```

The consuming mod should depend on MCWebUI APIs, not manage Chromium/CEF binaries itself.

This keeps storage cost, security updates, cache behavior and runtime ABI management centralized.

## Modpack support

Modpack authors must be able to avoid first-launch downloading.

Supported future deployment models may include:

- installer pre-populates `.minecraft/mcwebui/runtime/...`;
- pack ships the runtime as a separately declared optional/required package;
- launcher installs the package before Minecraft starts;
- administrators distribute a verified offline runtime archive.

MCWebUI should discover and validate a correctly preinstalled runtime without attempting a download.

The main mod JAR should remain independent from the large native runtime package so modpack systems can deduplicate and cache it efficiently.

## Security and integrity requirements

A production runtime installer must treat downloaded/imported runtime packages as untrusted until validation succeeds.

At minimum:

- HTTPS for official automatic sources;
- signed/release-controlled manifest metadata should be considered before production rollout;
- SHA-256 verification of runtime files;
- reject absolute archive paths;
- reject `..` traversal;
- reject drive-qualified paths and alternate roots;
- reject symbolic-link/reparse-point tricks unless intentionally supported and securely handled;
- extract only into a private staging directory;
- never execute helper binaries from staging before validation;
- atomic final publish where filesystem semantics allow it;
- do not overwrite an active validated runtime in place.

Hash validation protects against corruption and mismatched artifacts; it must not be described as a complete substitute for trusted distribution/signing.

## Interrupted installs and concurrency

The future implementation must account for:

- Minecraft terminated during download;
- download interrupted;
- extraction interrupted;
- two Minecraft instances attempt installation simultaneously;
- a previous staging directory remains after a crash;
- an existing runtime is corrupt;
- Windows has runtime DLLs locked by another process;
- update requested while the old runtime is still in use.

Suggested model:

```text
runtime-id.installing-<random>/
```

plus an installation lock scoped to the target runtime identity.

Only one validated completed directory becomes the canonical runtime. Cleanup of stale staging directories should be conservative and must not delete a runtime used by another active Minecraft process.

## Runtime updates

Runtime updates must be versioned side-by-side rather than performed by mutating loaded CEF files in place.

For example:

```text
cef-144.../
cef-145.../
```

A running Minecraft process keeps using the runtime it loaded. A newly installed version becomes eligible on a subsequent clean launch.

Old versions may later be garbage-collected only when they are not active and policy allows it.

Do not attempt hot-upgrading `libcef.dll` inside a running Java/Minecraft process.

## Backend selection and failure policy

Current experimental behavior remains the baseline:

- default backend remains MCEF while Direct CEF is not production-ready;
- explicit `direct-cef` selection does not silently fall back to MCEF when its required runtime is missing or invalid;
- runtime installation failure should present a recoverable setup path rather than disguising the backend failure.

A future production policy may introduce an explicit `AUTO` mode, but automatic backend fallback must be a deliberate product decision and not an implementation side effect.

## Lazy installation and lazy runtime initialization

The runtime should not need to be installed merely because an unrelated modpack contains the MCWebUI API if no Direct CEF feature is ever used.

Desired long-term behavior:

```text
MCWebUI present
↓
Direct CEF backend actually required
↓
runtime discovery / installation if needed
↓
runtime validation
↓
CEF initialization
```

Exact timing must be reconciled with the existing hidden prewarm strategy. Installation/download must never occur unexpectedly in the render loop or block a critical Minecraft frame without user-visible progress.

## User-visible manual install documentation

The release documentation should always publish:

- required runtime ID;
- supported OS/architecture;
- official runtime package filename;
- package SHA-256 or signed manifest identity;
- automatic installer behavior;
- offline package import steps;
- exact normal runtime directory for troubleshooting;
- how to remove a corrupt runtime safely;
- how modpack authors can preinstall it;
- how developers can override the runtime path.

Users should never have to infer which CEF distribution to download directly from upstream CEF build archives.

MCWebUI should distribute a known, tested runtime package that matches its JNI/native ABI and helper binary.

## Packaging boundary

Until this plan is implemented, retain the current invariant:

```text
Mod JAR native CEF leakage = 0
```

Do not start embedding CEF binaries into the mod JAR as a temporary shortcut.

The eventual release artifacts should conceptually be separated into:

```text
MCWebUI mod/API artifact
+
MCWebUI Direct CEF runtime package(s)
```

The runtime package can be large without making every mod JAR large, and one installed copy can serve all compatible MCWebUI consumers in that Minecraft instance.

## Proposed implementation phases

Current phase state: **Phase A-B-C IMPLEMENTED; RUNTIME R1 PUBLISHED; REAL_RELEASE PASS; MOD NOT PUBLISHED.**

### Phase A — manifest and discovery

- frozen runtime identity/schema v1;
- standard and explicit-override path resolution;
- complete-tree, size and streaming SHA-256 validation;
- validated preinstalled/manual directory runtime;
- explicit validated native-loading boundary;
- no network download or archive import.

### Phase B — offline package import

- import official ZIP/package;
- safe staging extraction;
- manifest/hash checks;
- atomic publish;
- recovery/error UX.

This phase should be completed before relying on automatic downloading so there is always a supported offline escape path.

### Phase C — automatic download

- project-owned descriptor model and generator: implemented;
- JAR-owned catalog and NeoForge descriptor embedding gate: implemented;
- deterministic release preparation/report/checksum pipeline: implemented;
- LOCAL_FIXTURE fresh-instance download/install acceptance: implemented and passed;
- HTTPS-only streaming download, progress/cancel/retry foundation: implemented;
- Phase B staging/import and integrity reuse: implemented;
- official Runtime R1 publication and production URL/size/SHA pin: completed;
- real production HTTPS download/import/discovery acceptance: passed;
- mirror/source expansion policy: not implemented.

### Phase D — pack/launcher integration

- document preinstallation contract;
- expose machine-readable manifest where useful;
- ensure shared-runtime deduplication;
- support launcher/modpack tooling without each mod bundling CEF.

### Phase E — update/cleanup policy

- side-by-side runtime upgrades;
- stale version cleanup;
- active-runtime detection;
- disk usage reporting;
- optional user-controlled cache location.

## Acceptance criteria for a future production implementation

The distribution system should not be called production-ready until all of the following are demonstrated:

1. A clean Minecraft instance can automatically install the required runtime and launch Direct CEF.
2. An offline instance can install the exact same runtime by importing an official package.
3. A preinstalled runtime is discovered without network access.
4. Corrupted files are rejected by integrity validation.
5. Interrupted installs do not poison the final runtime directory.
6. Two installation attempts cannot publish conflicting partial runtimes.
7. A consuming mod never needs to know where `libcef.dll` is stored.
8. Two MCWebUI-consuming mods reuse one runtime.
9. No CEF native runtime files leak into the normal Mod JAR unless the release model is intentionally changed.
10. Failure messages give normal players a retry/manual-install path instead of requiring them to debug native libraries.

## Current decision

For the current project stage, adopt the following design decision now:

> Direct CEF will use an external, shared, versioned MCWebUI runtime. Automatic first-use installation will be the preferred player experience, but manual/offline package import and preinstalled runtime discovery are mandatory supported workflows. Automatic downloading is not a hard runtime dependency.

Phase A is implemented and verified for prepared directories; Phase B is
implemented and verified for offline package import (safe staging, Phase A
re-validation, atomic publish with repair/rollback, standard rediscovery, and a
Minecraft setup screen). Phase C's descriptor/downloader/install core, JAR
catalog, release preparation pipeline, and LOCAL_FIXTURE first-run gate now
converge on that same importer and validated-directory boundary. Production
automatic download remains unavailable until an official package is published,
its real HTTPS URL/size/SHA-256 are pinned in MCWebUI, and that exact path passes
real-network acceptance. Manual offline import remains the supported path today.

The byte-exact Runtime R1 dry-run package, entrypoint hashes, included notices,
determinism evidence, and publication boundary are recorded in
`plans/direct-cef-runtime-r1-release.md`. Runtime R1 is published and immutable;
its URL and REAL_RELEASE evidence are recorded there. The MCWebUI mod is not
published.
