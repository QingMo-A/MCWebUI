package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.backend.PaintFrame;

/** Target-owned GPU texture port; the real NeoForge client implementation supplies this callback. */
@FunctionalInterface
public interface NeoForgeTextureUploader {
    void upload(PaintFrame frame);
}
