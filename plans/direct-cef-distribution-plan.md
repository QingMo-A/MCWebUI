# Direct CEF runtime distribution plan

Status: **DESIGN ONLY / NOT IMPLEMENTED** (2026-08-14).

This plan defines how MCWebUI should distribute and locate the external Direct CEF runtime once the experimental backend is productized. It deliberately does **not** implement a downloader, installer, updater, or native packaging pipeline yet.

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

The exact final directory names are still subject to implementation review, but the semantic layout should be equivalent to:

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

Each supported runtime must have a stable identity. A future manifest should include at least:

```json
{
  "runtimeId": "cef-144.0.33-cb4715c",
  "mcwebuiRuntimeAbi": 1,
  "cefVersion": "144.0.33",
  "chromiumVersion": "144.0.7559.259",
  "platform": "windows",
  "arch": "x86_64",
  "files": [
    {
      "path": "libcef.dll",
      "size": 0,
      "sha256": "..."
    }
  ]
}
```

The concrete schema may change, but the following semantics are required:

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

### Phase A — manifest and discovery

- freeze runtime identity/schema;
- implement runtime path resolution;
- implement validation only;
- support preinstalled/manual directory runtime;
- no network download yet.

### Phase B — offline package import

- import official ZIP/package;
- safe staging extraction;
- manifest/hash checks;
- atomic publish;
- recovery/error UX.

This phase should be completed before relying on automatic downloading so there is always a supported offline escape path.

### Phase C — automatic download

- official source metadata;
- progress/cancel/retry;
- staging install;
- integrity validation;
- mirrors/source policy if required.

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

Implementation remains deferred until the current Direct CEF experimental Minecraft runtime has completed its hardening/regression phase.
