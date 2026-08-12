# Native proof workflow

`patches/jcef-a78e832-game-sync.patch` and `patches/mcef-2.1.6-game-sync.patch`
are generated, apply-checkable source patches. They are not bundled binaries.
The Windows proof wrapper built from JCEF `a78e832` and CEF 5845 produced
`jcef.dll` SHA-256 `E893C5A9AEA5C4230820DB564FD92C37D757B012EB359F14E5F647464F3FF1CC`.
Its exports include both `N_SendExternalBeginFrame` and the native capability
marker `N_SupportsExternalBeginFrame`. A patched JCEF Java proof jar was also
compiled from that checkout (SHA-256
`A2536340224814F74820C4C39E060B8B0E6E067C35AAC849B43E8F40327D7BD0`).
The pinned MCEF root build remains historically blocked by its Gradle 8.8/Loom
combination. `scripts/frame-proof/build-mcef-standalone.ps1` now substitutes only
that build system with a temporary NeoForge ModDevGradle project while compiling
the exact MCEF `c89e242` and JCEF `a78e832` sources plus the committed patches.
The resulting external proof JAR is `F:\Temp\mcef-standalone-proof.jar`, SHA-256
`1519223E9780BEF8AB3FAD45D5FAC625656CB59E0C63BAFFCA373CD02A2C1677`, 207 entries.
The standalone artifact has been launched once with NeoForge 1.21.1: the proof
JCEF directory was accepted and CEF reached `INITIALIZED`. The run did not reach
the F8 showcase under the automated desktop session, so external-frame, rAF, and
paint-rate metrics remain **NOT VERIFIED**; no FPS claim is made. No binary is
copied into this repository.

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
