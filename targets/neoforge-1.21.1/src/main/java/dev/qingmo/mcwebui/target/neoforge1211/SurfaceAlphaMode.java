package dev.qingmo.mcwebui.target.neoforge1211;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

/** Target-local browser texture alpha contract and its Minecraft blend policy. */
enum SurfaceAlphaMode {
    OPAQUE(false, null, null, null, null),
    PREMULTIPLIED(true,
            BlendFactor.ONE,
            BlendFactor.ONE_MINUS_SRC_ALPHA,
            BlendFactor.ONE,
            BlendFactor.ONE_MINUS_SRC_ALPHA);

    private final boolean blendEnabled;
    private final BlendFactor sourceRgb;
    private final BlendFactor destinationRgb;
    private final BlendFactor sourceAlpha;
    private final BlendFactor destinationAlpha;

    SurfaceAlphaMode(boolean blendEnabled,
                     BlendFactor sourceRgb,
                     BlendFactor destinationRgb,
                     BlendFactor sourceAlpha,
                     BlendFactor destinationAlpha) {
        this.blendEnabled = blendEnabled;
        this.sourceRgb = sourceRgb;
        this.destinationRgb = destinationRgb;
        this.sourceAlpha = sourceAlpha;
        this.destinationAlpha = destinationAlpha;
    }

    void apply() {
        if (!blendEnabled) {
            RenderSystem.disableBlend();
            return;
        }
        RenderSystem.enableBlend();
        // CEF supplies premultiplied RGB. Multiplying by SRC_ALPHA again would
        // darken translucent panels, antialiased text and rounded edges.
        RenderSystem.blendFuncSeparate(sourceFactor(sourceRgb), destinationFactor(destinationRgb),
                sourceFactor(sourceAlpha), destinationFactor(destinationAlpha));
    }

    void restore() {
        if (blendEnabled) RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    boolean blendEnabled() { return blendEnabled; }
    BlendFactor sourceRgb() { return sourceRgb; }
    BlendFactor destinationRgb() { return destinationRgb; }
    BlendFactor sourceAlpha() { return sourceAlpha; }
    BlendFactor destinationAlpha() { return destinationAlpha; }

    private static GlStateManager.SourceFactor sourceFactor(BlendFactor factor) {
        return switch (factor) {
            case ONE -> GlStateManager.SourceFactor.ONE;
            case ONE_MINUS_SRC_ALPHA -> GlStateManager.SourceFactor.ONE_MINUS_SRC_ALPHA;
        };
    }

    private static GlStateManager.DestFactor destinationFactor(BlendFactor factor) {
        return switch (factor) {
            case ONE -> GlStateManager.DestFactor.ONE;
            case ONE_MINUS_SRC_ALPHA -> GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA;
        };
    }

    enum BlendFactor {
        ONE,
        ONE_MINUS_SRC_ALPHA
    }
}
