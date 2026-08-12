# Native proof workflow

`patches/jcef-a78e832-game-sync.patch` and `patches/mcef-2.1.6-game-sync.patch`
are generated, apply-checkable source patches. They are not bundled binaries.
The Windows proof wrapper built from JCEF `a78e832` and CEF 5845 produced
`jcef.dll` SHA-256 `E893C5A9AEA5C4230820DB564FD92C37D757B012EB359F14E5F647464F3FF1CC`.
Its exports include both `N_SendExternalBeginFrame` and the native capability
marker `N_SupportsExternalBeginFrame`. A Java-only proof jar was also compiled
from that checkout (SHA-256
`8E6678AE346859ADF3041DB185D3056B983F4E87CBFE1772007C594C461D132A`).
The pinned MCEF NeoForge build was **not** produced: its Gradle 8.8 wrapper
cannot select the current Loom 1.7-SNAPSHOT variant, while Gradle 8.13 reaches
an incompatible Loom Problems API. This is therefore a patch/build checkpoint,
not a runnable proof artifact set.

To run the proof target, provide a matched patched MCEF jar through
`MCWEBUI_PATCHED_MCEF_JAR` and its native directory through
`MCWEBUI_PATCHED_NATIVE_DIR` (the parent containing `windows_amd64/`), plus their expected hashes through
`MCWEBUI_PATCHED_MCEF_SHA256` and `MCWEBUI_PATCHED_JCEF_SHA256`, then pass
`-PmcwebuiFramePacingProof=true`. Missing artifacts fail fast; default builds
continue to use stock MCEF and callback-driven paints.

No binary or third-party source checkout is committed here. CEF/java-cef use
their BSD-style license; redistribution must retain their copyright and license
notices. MCEF is LGPL-2.1-or-later; distributing a modified MCEF artifact must
preserve its notices and provide the corresponding modified library source (the
patch alone is the source of truth in this repository). A future native archive
must ship the matching licenses and checksums together with every platform build.
