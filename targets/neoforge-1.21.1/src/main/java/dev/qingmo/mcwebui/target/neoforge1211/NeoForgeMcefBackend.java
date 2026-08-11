package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.backend.BrowserBackend;
import dev.qingmo.mcwebui.backend.BrowserSurface;
import dev.qingmo.mcwebui.backend.BrowserSurfaceListener;
import dev.qingmo.mcwebui.runtime.WebViewConfig;

/**
 * NeoForge MCEF integration seam. The maintained CCBlueX MCEF line currently does not publish a
 * verified NeoForge 1.21.1 artifact, so construction fails explicitly until that backend is selected.
 * Keeping this boundary in the target module prevents MCEF types from leaking into common.
 */
public final class NeoForgeMcefBackend implements BrowserBackend {
    private final String artifact;

    public NeoForgeMcefBackend() {
        this("com.github.CCBlueX:mcef:3.1.0-1.21.4");
    }

    public NeoForgeMcefBackend(String artifact) {
        this.artifact = artifact == null ? "unknown" : artifact;
    }

    @Override
    public BrowserSurface createSurface(WebViewConfig config, BrowserSurfaceListener listener) {
        throw new UnsupportedOperationException("No verified MCEF artifact for NeoForge 1.21.1; selected candidate " + artifact);
    }

    public String artifact() { return artifact; }
}
