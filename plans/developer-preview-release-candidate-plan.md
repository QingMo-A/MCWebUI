# Developer Preview source release candidate

## Scope

This checkpoint prepares an auditable NeoForge source candidate. It does not
publish MCWebUI or the external Direct CEF runtime. Candidate files live under
the ignored `build/release-candidate` directory.

## Compatibility gate

1. Accept only Windows x86_64 host aliases at the static gate.
2. Probe the current Minecraft render-thread WGL context without loading Direct
   CEF, CEF, or creating a browser.
3. Require `WGL_NV_DX_interop`, `WGL_NV_DX_interop2`, and open/close/register/
   unregister/access/lock/unlock entry points.
4. Treat NOT_PROBED, UNSUPPORTED, and PROBE_FAILED as incompatible. Do not offer
   runtime download or prewarm in those states.
5. Keep MCEF first in AUTO mode. GPU identity is diagnostic only.

Current automated graphics evidence is NVIDIA-only. AMD and Intel are not yet
verified. There is no CPU readback/copy fallback.

## Candidate procedure

1. Commit all intended source and documentation changes; leave tracked status
   clean.
2. Confirm the default `prepareDeveloperPreviewCandidate` invocation rejects
   `0.1.0-SNAPSHOT`.
3. Run the task with quoted `-PmcwebuiVersion=0.1.0-preview.1`.
4. Require frontend typecheck/build, all target tests/builds, architecture gate,
   independent binary consumer proof, and native/runtime JAR leakage audit.
5. Inspect `candidate-report.json`, `checksums.txt`, and the copied candidate JAR.
6. Confirm the report exact Git SHA and checksum, unconfigured production
   runtime source, and publication blocked waiting for an official runtime.

No tag, GitHub Release, runtime upload, Maven publication, or public mod artifact
is authorized by this plan.
