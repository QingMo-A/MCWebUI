# Direct CEF Runtime R1 release lock

Status: **PUBLISHED** (2026-08-16)

This is the operator record for the exact external Runtime R1 artifact prepared
and published on 2026-08-16. It records immutable inputs and evidence; it is not
the runtime binary and does not authorize publication of the MCWebUI mod.

## Source and identity

- Source Git SHA: `740958afd63183e28e5b4d8168178ab7fb728d19`
- Runtime ID: `cef-144.0.33-cb4715c`
- MCWebUI runtime ABI: `1`
- CEF: `144.0.33`
- Chromium: `144.0.7559.259`
- Platform: `windows`
- Architecture: `x86_64`
- Artifact revision: `1`

`runtimeId` identifies the pinned CEF line. `artifactRevision` distinguishes
different distributed shim/helper/payload bytes without migrating manifest
schema v1.

## Frozen prepared package

- Filename: `mcwebui-direct-cef-runtime-cef-144.0.33-cb4715c-windows-x86_64-1.zip`
- Compressed size: `164216473` bytes
- Unpacked payload size: `394723009` bytes
- Runtime file count: `241`
- ZIP SHA-256: `6B9DE0EF90869DB7C5966A6307516A886EB15B1AC6ED14BDBFD987DB912D4D6B`

The external prepared tree and ZIP are not tracked by Git. The ZIP must not be
rebuilt, renamed, or modified during an authorized release; upload exactly the
frozen bytes above.

The machine-readable authority is
`plans/direct-cef-runtime-r1-lock.json` (`lockVersion: 1`). Before any authorized
upload, run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\direct-cef-runtime\verify-runtime-release-inputs.ps1 `
  -Path <exact-frozen-r1-zip>
```

The wrapper must print `FROZEN RUNTIME UPLOAD PRECHECK: PASS`. It verifies the
whole-file identity, safe/exact ZIP entry set, manifest identity, all manifest
payload size/hashes, entrypoint hashes, notices, and unpacked payload total.
Here, file count `241` means the distributed `runtime.json.files[]` payload;
the ZIP has exactly one additional entry, `runtime.json`. A failure stops the
release and does not authorize rebuilding a substitute.

The guarded operator is
`scripts/direct-cef-runtime/publish-runtime-r1.ps1`. Its default mode is a
side-effect-free dry run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\direct-cef-runtime\publish-runtime-r1.ps1 `
  -Path <exact-frozen-r1-zip>
```

It always runs the frozen verifier first, then requires a clean tracked tree,
the exact upstream repository, authenticated `gh`, and no local/remote tag,
Release, or asset collision. Only an explicitly user-authorized invocation with
`-Publish` can enter the mutation branch. That branch targets the runtime source
commit recorded by the lock, never the later operator/docs HEAD; rechecks local
bytes immediately before mutation; never uses `--clobber`; and downloads the
published asset to a fresh temporary path for size/SHA verification. A remote
verification failure requires manual investigation and is never auto-deleted or
overwritten.

Publication success also requires source provenance verification from the
actual remote `origin` tag. The operator resolves both lightweight tags (direct
ref) and annotated tags (peeled `^{}` ref, preferred when present), then requires
the final 40-character commit to equal the lock's Runtime source SHA
`740958afd63183e28e5b4d8168178ab7fb728d19`. Empty, duplicate, malformed, or
mismatched results fail with `REMOTE_TAG_TARGET_UNRESOLVED` or
`REMOTE_TAG_TARGET_MISMATCH` before any success message. Release metadata is
validated with that provenance as one state gate; the downloaded asset SHA
remains a separate final gate. The operator removes only its own remote-download
temporary directory in `finally`; cleanup failure is a warning and never hides
the actual publication/verification failure or touches the input ZIP.

## Entrypoints

| Entrypoint | Size | SHA-256 |
| --- | ---: | --- |
| `mcwebui-direct-cef.dll` | 840192 | `F144E0CFC25E6D00D7970060E564B2BA3E5A8CD8AAE34099EB3004837E6BA064` |
| `mcwebui-cef-helper.exe` | 731136 | `B97EFDDA3421061F7652703BF7442FA0FBBAF8842B1F954B14A5ABE6E0AF68D2` |
| `libcef.dll` | 253349888 | `5A5556425AD319735175BB6CA386813B73AD515D49081BDE6FC0423F034D8EBE` |
| `chrome_elf.dll` | 1864704 | `F2AD0E77044D360E72FE3101F9ACD11F302AF54BD97DB080694B3D5764543026` |

## License and notices

Included from the exact official CEF binary distribution:

- `LICENSE.txt`
- `CREDITS.html` (third-party Chromium notices)

No legal conclusion is asserted; this records that the distribution-provided
license/notices were included in the frozen payload.

## Verification

- Clean native Release build: **PASS**
- Native lifecycle smoke: **PASS** (exit 0, ready, copy/GL failures 0)
- Phase A manifest validation: **PASS**
- Phase B import using the frozen ZIP: **PASS**
- Phase C descriptor/download/import/discovery LOCAL_FIXTURE: **PASS**
- Deterministic package, repack 1:
  `6B9DE0EF90869DB7C5966A6307516A886EB15B1AC6ED14BDBFD987DB912D4D6B`
- Deterministic package, repack 2:
  `6B9DE0EF90869DB7C5966A6307516A886EB15B1AC6ED14BDBFD987DB912D4D6B`
- Byte-identical: **YES**
- Windows x86_64 static compatibility: **PASS**
- Current NVIDIA host WGL interop/interop2/entrypoints: **PASS**
- Unsupported compatibility test matrix: **PASS**
- Default SNAPSHOT candidate rejection: **PASS**
- `0.1.0-preview.1` candidate: **PASS**
- Independent binary consumer: **PASS**
- Frozen verifier synthetic failure matrix: **PASS (11/11)**
- Exact local frozen ZIP upload precheck: **PASS**
- Guarded operator pure/synthetic tests: **PASS (20/20)** (re-run 2026-08-16)
- Real guarded operator dry run: **PASS / NO REMOTE CHANGES**
- Authorized guarded operator mutation: **CREATED tag, Release, and exact asset**
- Operator-integrated `gh release download`: **INTERRUPTED after remote creation**
  because this host's single transfer made no progress; no retry, deletion,
  overwrite, or second publication was attempted
- Independent anonymous public HTTPS download: **PASS**, reconstructed bytes
  match the frozen size and SHA-256 exactly
- Fresh-instance REAL_RELEASE HTTPS download/import/discovery: **PASS**
- NeoForge use of that installed runtime: **PASS** for standard discovery,
  bundled page, Bridge handshake, accelerated interop, and hidden prewarm

The standalone native smoke has no Minecraft WGL context, so its interop status
is expected to be unsupported. The separate real Minecraft compatibility run
is the graphics evidence. Current automated vendor evidence is NVIDIA-only;
AMD and Intel are not verified and no CPU fallback exists.

## Publication state

- Runtime-only tag/release name: `direct-cef-runtime-r1`
- Asset filename: exactly the frozen filename above
- Production URL: `https://github.com/QingMo-A/MCWebUI/releases/download/direct-cef-runtime-r1/mcwebui-direct-cef-runtime-cef-144.0.33-cb4715c-windows-x86_64-1.zip`
- GitHub Release: `https://github.com/QingMo-A/MCWebUI/releases/tag/direct-cef-runtime-r1`
- Git tag: **CREATED**, remote target `740958afd63183e28e5b4d8168178ab7fb728d19`
- Asset uploaded: **YES**, exactly one asset, `164216473` bytes
- Remote digest: `sha256:6b9de0ef90869db7c5966a6307516a886eb15b1ac6ed14bdbfd987db912d4d6b`
- Anonymous public HTTPS byte download: **PASS**, size and SHA match the lock
- Final configured descriptor: `gradle/direct-cef-runtime-r1.release.json`
- REAL_RELEASE download/import/discovery: **PASS** against a fresh instance
- NeoForge standard discovery, bundled page, Bridge handshake, accelerated texture/interoperability and hidden prewarm: **PASS**
- Manual Setup Screen visual acceptance: **MANUAL USER ACCEPTANCE REQUIRED**
- MCWebUI mod publication: **NOT PERFORMED**

SHA-256 pins content identity and integrity; it does not by itself prove
publisher authenticity. The future JAR-owned descriptor supplies the trusted
project pin, while GitHub HTTPS is only the transport.

Runtime R1 is immutable. Do not rebuild, replace, delete, or re-upload it. The
remaining release work is manual Setup Screen acceptance and a separately
authorized MCWebUI mod publication; no Modrinth, CurseForge, Maven, or mod
release action occurred in this checkpoint.
