package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.backend.BrowserSurface;

/** NeoForge-only rendering capability backed by MCEF's native OpenGL texture. */
public interface NeoForgeRenderableSurface extends BrowserSurface {
    int textureId();
}
