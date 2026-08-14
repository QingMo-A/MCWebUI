package dev.qingmo.mcwebui.target.neoforge1211;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceAlphaModeTest {
    @Test
    void opaqueSurfaceDoesNotEnableCompositionBlend() {
        assertFalse(SurfaceAlphaMode.OPAQUE.blendEnabled());
    }

    @Test
    void premultipliedSurfaceUsesOneOverOneMinusSourceAlpha() {
        SurfaceAlphaMode mode = SurfaceAlphaMode.PREMULTIPLIED;
        assertTrue(mode.blendEnabled());
        assertEquals(SurfaceAlphaMode.BlendFactor.ONE, mode.sourceRgb());
        assertEquals(SurfaceAlphaMode.BlendFactor.ONE_MINUS_SRC_ALPHA, mode.destinationRgb());
        assertEquals(SurfaceAlphaMode.BlendFactor.ONE, mode.sourceAlpha());
        assertEquals(SurfaceAlphaMode.BlendFactor.ONE_MINUS_SRC_ALPHA, mode.destinationAlpha());
    }
}
