package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.backend.BrowserSurface;

/** NeoForge render-thread contract for the opt-in direct CEF GPU surface. */
interface DirectCefRenderableSurface extends BrowserSurface {
    boolean beginRenderFrame();
    void endRenderFrame();
    int textureId();
    String alphaMode();
    boolean yFlipped();
}
