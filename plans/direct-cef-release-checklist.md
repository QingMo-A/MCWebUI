# Direct CEF runtime release checklist

Status: **RELEASE PIPELINE READY / PRODUCTION SOURCE NOT CONFIGURED** (2026-08-14).

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

These steps are intentionally **not executed by the current checkpoint**:

1. With explicit user authorization, create the runtime-only tag/GitHub Release.
2. Upload exactly the previously verified deterministic ZIP; do not rebuild it after calculating its identity.
3. Obtain the final HTTPS asset URL.
4. Re-run `generate-release-descriptor.ps1` against the exact uploaded ZIP with the same artifact revision and final URL.
5. Compare the regenerated descriptor's package size/SHA/runtime payload identity with the release report. A mismatch stops the release.

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

Current verdict: **RELEASE READY / WAITING FOR OFFICIAL ASSET**. The Developer Preview gate is not yet satisfied.
