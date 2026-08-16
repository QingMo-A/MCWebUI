# Direct CEF Runtime R1 release lock

Status: **FROZEN / NOT PUBLISHED**

This is the operator record for the exact external Runtime R1 artifact prepared
on 2026-08-16. It records immutable inputs and evidence; it is not the runtime
binary and does not authorize a tag, GitHub Release, upload, or mod publication.

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

The standalone native smoke has no Minecraft WGL context, so its interop status
is expected to be unsupported. The separate real Minecraft compatibility run
is the graphics evidence. Current automated vendor evidence is NVIDIA-only;
AMD and Intel are not verified and no CPU fallback exists.

## Publication state

- Recommended runtime-only tag/release name: `direct-cef-runtime-r1`
- Asset filename: exactly the frozen filename above
- Production URL: `UNCONFIGURED`
- GitHub Release: `NOT CREATED`
- Git tag: `NOT CREATED`
- Asset uploaded: `NO`
- Final configured descriptor: `NOT GENERATED`
- REAL_RELEASE: `NOT RUN`
- Manual Setup acceptance against the official asset: `NOT RUN / READY`

SHA-256 pins content identity and integrity; it does not by itself prove
publisher authenticity. The future JAR-owned descriptor supplies the trusted
project pin, while GitHub HTTPS is only the transport.

The only authorized next sequence is: create the exact runtime-only release,
upload this exact ZIP, obtain the stable HTTPS URL, generate the final descriptor
without changing size/hash, build the descriptor-bearing mod candidate, run
REAL_RELEASE and manual Setup acceptance, and only then consider publication.
