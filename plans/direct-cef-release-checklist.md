# Direct CEF runtime release checklist

Status: **RUNTIME R1 PUBLISHED / MOD NOT PUBLISHED** (2026-08-16).

This is the operator checklist for publishing the external Direct CEF runtime and then a MCWebUI NeoForge Developer Preview. It does not authorize creating a tag, GitHub Release, uploading an asset, or publishing a mod artifact. Those actions require explicit user approval.

## Release ordering

The runtime asset must exist before the final mod JAR can pin its immutable URL, size, and SHA-256. Use two ordered release steps:

1. publish a runtime-only tag/release and upload the deterministic runtime ZIP;
2. obtain the stable HTTPS asset URL, regenerate the descriptor from the unchanged ZIP, embed that descriptor, and build the final MCWebUI mod artifact.

The HTTPS URL is transport location, not content identity. The JAR-owned descriptor pins the runtime requirement, artifact revision, exact compressed size, unpacked payload size, and SHA-256. Redirects remain subject to the downloader policy and the final bytes must still match.

## Runtime asset preparation

1. Check out the exact intended bridge/release commit and record `git rev-parse HEAD`.
2. Confirm the worktree contains no unrelated or uncommitted release inputs.
3. Perform a clean native Direct CEF runtime build with the pinned CEF 144.0.33 / Chromium 144.0.7559.259 SDK and approved toolchain.
4. Run the native lifecycle smoke and require exit 0 with no copy/interop failure regression.
5. Assemble a clean prepared runtime directory containing only the distributable DLL/helper/CEF resources; exclude the SDK, standalone smoke executable, caches, logs, and build intermediates.
6. Generate `runtime.json` with `generate-runtime-manifest.ps1`.
7. Validate the prepared tree against the manifest: complete file set, entrypoints, exact sizes and SHA-256, no reparse points or unexpected files.
8. Run `prepare-runtime-release.ps1 -RuntimeRoot <prepared> -ArtifactRevision <revision> -OutputDirectory <external-staging>` with no URL for the first pass.
9. Require deterministic repackaging PASS. Record ZIP filename, compressed size, unpacked payload size, SHA-256, file count, entrypoints, artifact revision, and source Git SHA from `release-report.json`.
10. Review `checksums.txt`, the unconfigured descriptor candidate, and the report. Never commit the ZIP or release staging directory.

Any change to `mcwebui-direct-cef.dll`, helper, CEF files, resources, or packaging payload requires a new `artifactRevision`, even if the CEF version is unchanged.

## Authorized runtime release step

These steps were executed for Runtime R1 under explicit user authorization:

1. Run `verify-runtime-release-inputs.ps1 -Path <frozen-r1.zip>` and require
   `FROZEN RUNTIME UPLOAD PRECHECK: PASS`. The verifier reads the tracked R1
   lock and performs no build, repack, extraction-to-runtime, or mutation. If it
   fails, stop the release; do not rebuild replacement bytes and continue.
2. Run `publish-runtime-r1.ps1 -Path <frozen-r1.zip>` without `-Publish` and
   require `RUNTIME R1 RELEASE DRY RUN PASS` plus
   `NO REMOTE CHANGES WERE MADE`.
3. Only with explicit user authorization, rerun the same guarded operator with
   `-Publish`. Do not construct an ad-hoc `gh release create` command. The
   operator fixes the repository, tag, target source commit, filename, size,
   hash, title, and notes from the tracked lock; refuses collisions and
   `--clobber`; resolves the actual remote tag target (lightweight or annotated)
   and requires it to equal the lock's Runtime source SHA; and verifies the
   downloaded remote asset after upload. Tag provenance or asset verification
   failure is an immutable-release incident: stop for manual investigation and
   do not delete, move, overwrite, or recreate the remote state automatically.
4. Require the operator to report the exact tag, Release URL, asset URL, size,
   resolved remote source commit, and downloaded remote SHA. It may print
   `OFFICIAL RUNTIME R1 RELEASE PUBLISHED` only after both provenance and asset
   SHA pass. It uploads only the frozen ZIP; do not perform a second manual
   upload or rebuild after calculating its identity.
5. Retain the stable HTTPS asset URL reported by the verified Release.
6. Re-run `generate-release-descriptor.ps1` against the exact uploaded ZIP with the same artifact revision and final URL.
7. Compare the regenerated descriptor's package size/SHA/runtime payload identity with the release report. A mismatch stops the release.

## Final NeoForge artifact

1. Build with `-PmcwebuiDirectCefReleaseDescriptor=<absolute-path-to-final-descriptor>`.
2. The build must validate the descriptor through the same Java parser/requirement checks used by the runtime catalog before copying it to `META-INF/mcwebui/direct-cef-runtime-release.json`.
3. Inspect the NeoForge JAR: exactly one small descriptor is present.
4. Confirm the Forge artifact has no Direct CEF release descriptor unless the shared-artifact design is explicitly changed later.
5. Audit all mod JARs for native leakage. `libcef.dll`, `chrome_elf.dll`, `mcwebui-direct-cef.dll`, helper executables, PAK/locales, SDK content, and runtime ZIPs must have zero entries.
6. Run the fresh-instance harness in `REAL_RELEASE` mode against the actual HTTPS asset. Require download, exact size/SHA, Phase B import, Phase A standard discovery, bundled page, Bridge handshake, and hidden prewarm.
7. Perform manual Setup Screen acceptance: text/layout, Download & Install, progress, cancellation, retry, offline Import Package, Continue, and invalid override guidance.
8. Re-run existing Direct WebScreen regression/visual acceptance before publishing the mod artifact.

## Required regression matrix

- Phase A manifest/discovery/validated load tests;
- Phase B offline importer, concurrency, repair/rollback, and deterministic package tests;
- Phase C catalog, descriptor, downloader, redirects, cancellation, disk budget, and cleanup tests;
- native Direct CEF build and smoke;
- frontend typecheck/build;
- common, Forge, and NeoForge tests/builds;
- LOCAL_FIXTURE fresh-instance acceptance;
- REAL_RELEASE fresh-instance acceptance;
- descriptor-present and descriptor-absent JAR audits;
- native leakage count zero.

## Developer Preview gate

`DIRECT CEF DEVELOPER PREVIEW READY` may be declared only when all are true:

- Phase A, Phase B, and Phase C core tests pass;
- an official runtime asset exists;
- the final mod JAR bundles a valid project-owned descriptor for that exact asset;
- fresh-instance `REAL_RELEASE` acceptance passes;
- manual Setup Screen install acceptance passes;
- existing Direct WebScreen regression passes.

Current verdict: **OFFICIAL RUNTIME AVAILABLE / MANUAL SETUP ACCEPTANCE REQUIRED**.
The runtime-only Release, pinned descriptor, REAL_RELEASE download,
import/discovery, and NeoForge hidden-prewarm gates pass. The Developer Preview
mod is not published and its manual Setup Screen gate remains open.

The exact Runtime R1 inputs, public URL, provenance, and hashes are recorded in
`plans/direct-cef-runtime-r1-release.md`. Runtime R1 is immutable; do not
overwrite or recreate it.

## Source Developer Preview candidate checkpoint

- Static support is Windows x86_64 only; Windows ARM64/x86 and non-Windows
  hosts stop before runtime discovery or Setup.
- The current render-thread OpenGL context must expose both required WGL/NV
  interop extensions and all used entry points. `NOT_PROBED`, `UNSUPPORTED`,
  and `PROBE_FAILED` are not runtime-missing states and must not offer download.
- AUTO still chooses installed MCEF first. Hidden prewarm starts only after the
  static and graphics gates report supported.
- NVIDIA RTX 5060 Ti is the only automated vendor evidence at this checkpoint;
  AMD and Intel are **NOT VERIFIED**. Do not infer a vendor restriction from
  the diagnostic strings, and do not claim a CPU fallback.
- Run the default candidate task once and require its SNAPSHOT rejection. Then
  run `prepareDeveloperPreviewCandidate` with quoted
  `-PmcwebuiVersion=0.1.0-preview.1` from a clean tracked worktree.
- Audit `candidate-report.json`, `checksums.txt`, and the copied NeoForge JAR.
  The report must contain the exact Git SHA, SHA-256, zero native leakage,
  `productionRuntimeSource=CONFIGURED`, and zero native leakage.
- Candidate output is local evidence only. Do not create a tag, release, asset,
  Maven publication, or mod distribution without explicit user authorization.
