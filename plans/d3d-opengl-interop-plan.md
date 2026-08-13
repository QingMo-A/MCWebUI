# Standalone D3D/OpenGL WebScreen interop proof

Status: **AUTOMATED PASS on the local NVIDIA host; manual visual acceptance
remains required.**

This is proof-only Windows code. It does not connect Minecraft, JNI, targets,
MCEF/JCEF, or the production backend.

## Boundary and ownership

`--opengl-interop` starts one dedicated GL thread with a private WGL context
and hidden top-level window/DC. It consumes the existing host-owned three-slot
D3D11 mailbox. The CEF producer never waits for GL: it excludes the latest and
currently consumed slots, and drops when no writable slot exists. The GL thread
acquires one latest slot, registers it as a read-only `GL_TEXTURE_2D`, locks,
draws, unlocks, and releases the slot. It may repeat the latest generation.

No `glTexSubImage`, CPU texture upload, or per-frame CPU readback exists.
`glReadPixels` runs once for the fixed five-point alpha proof.

## Capability evidence

| Field | Observed |
| --- | --- |
| GL vendor | NVIDIA Corporation |
| GL renderer | NVIDIA GeForce RTX 5060 Ti/PCIe/SSE2 |
| GL version | 4.6.0 NVIDIA 591.86 |
| WGL extensions | WGL_NV_DX_interop, WGL_NV_DX_interop2 |
| DXGI adapter/LUID | NVIDIA GeForce RTX 5060 Ti / high 0, low 59869 |
| wglDXOpenDeviceNV | succeeded; last error 0 |
| complete-run registration | 3/3 expected slots |
| complete-run lock/unlock failures | 0/0 |

AMD/Intel fallback is not implemented. Missing extension, entry point, device,
registration, lock, or unlock is reported as `UNSUPPORTED` or `FAILED`; there
is no CPU fallback.

## Automated evidence

- Visible 60-target run: WGL capability PASS, three-slot registration PASS,
  lock/unlock PASS, real CEF premultiplied raw alpha PASS (5/5), full-frame
  fixed-blue GL composition PASS (5/5), RGBA mapping PASS, fixed
  `textureYFlipped=true`, and native input matrix PASS (64 native messages).
- GL target smokes (bounded, headless): 60 -> 102 frames, 86 new, 16 repeat,
  7 drops; 120 -> 205 frames, 109 new, 96 repeat, 12 drops; 144 -> 248
  frames, 110 new, 138 repeat, 12 drops. These are GL presentation smokes,
  never Web FPS claims.
- Ten bounded 700 ms headless lifecycles: all status READY/load true, balanced
  lock/unlock, zero lock/unlock failures, and no residual proof process.
- Existing D3D-only alpha/input regression remained PASS.
- CEF 5845 clean build/smoke remains CPU-only (configured 60, accelerated 0);
  OpenGL was not attempted on that compatibility path.
- Frontend typecheck/build, all target tests/builds, and JAR native-leak audit
  remain PASS (0 packaged native runtime files).

## Human gate and next step

World reveal, rounded corners, overlays, physical scanout, IME, clipboard, and
manual visible inspection remain **READY FOR USER ACCEPTANCE**. The proof does
not claim Minecraft integration or JNI. The next authorized work is only a thin
standalone JNI/interop design.
