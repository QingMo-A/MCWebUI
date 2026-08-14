package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.backend.BrowserSurface;

/** NeoForge-only rendering capability backed by MCEF's native OpenGL texture. */
public interface NeoForgeRenderableSurface extends BrowserSurface {
    int textureId();

    /** Acquire the latest texture for this render opportunity. */
    default boolean beginRenderFrame() { return true; }

    /** Release a texture acquired by {@link #beginRenderFrame()}. */
    default void endRenderFrame() { }

    /** Records that Minecraft actually submitted the acquired texture. */
    default void markFrameDrawn() { }

    default SurfaceAlphaMode alphaMode() { return SurfaceAlphaMode.OPAQUE; }

    default boolean yFlipped() { return false; }
}
