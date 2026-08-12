package dev.qingmo.mcwebui.target.neoforge1211;

import org.junit.jupiter.api.Test;
import dev.qingmo.mcwebui.runtime.WebViewLifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the GUI-coordinate contract used by the browser surface and Screen input routing. */
class NeoForgeWebSessionViewportTest {
    @Test
    void externalFrameOpportunityRequiresVisibleActiveSurface() {
        assertTrue(NeoForgeWebSession.shouldBeginFrame(false, true, WebViewLifecycle.VISIBLE, true));
        assertFalse(NeoForgeWebSession.shouldBeginFrame(false, false, WebViewLifecycle.VISIBLE, true));
        assertFalse(NeoForgeWebSession.shouldBeginFrame(false, true, WebViewLifecycle.HIDDEN, true));
        assertFalse(NeoForgeWebSession.shouldBeginFrame(true, true, WebViewLifecycle.VISIBLE, true));
        assertFalse(NeoForgeWebSession.shouldBeginFrame(false, true, WebViewLifecycle.VISIBLE, false));
    }
    @Test
    void browserViewportSupportsGuiAndFramebufferDensity() {
        int guiWidth = 1280;
        int guiHeight = 720;
        double guiScale = 2.0;

        // GUI mode intentionally keeps logical dimensions, while framebuffer mode restores the
        // physical-equivalent viewport for the same window at different GUI scales.
        assertEquals(guiWidth, NeoForgeWebSession.browserDimension(guiWidth, guiScale, true));
        assertEquals(guiHeight, NeoForgeWebSession.browserDimension(guiHeight, guiScale, true));
        assertEquals(2560, NeoForgeWebSession.browserDimension(2560, 1.0, false));
        assertEquals(2560, NeoForgeWebSession.browserDimension(1280, 2.0, false));
        assertEquals(318.5, NeoForgeWebSession.mapCoordinate(318.5, guiWidth, guiWidth));
    }

    @Test
    void framebufferViewportMapsCurrentGuiCoordinatesByRatio() {
        assertEquals(640.0, NeoForgeWebSession.mapCoordinate(960.0, 1920, 1280));
        // Use independent extents so a width-based mapping cannot accidentally pass for y.
        assertEquals(300.0, NeoForgeWebSession.mapCoordinate(450.0, 900, 600));
    }
}
