package dev.qingmo.mcwebui.target.neoforge1211;

import org.junit.jupiter.api.Test;
import dev.qingmo.mcwebui.runtime.WebViewLifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void minecraftWheelNotchesUseWindowsCefDeltaUnits() {
        assertEquals(120.0, NeoForgeWebSession.cefWheelDelta(1.0));
        assertEquals(-120.0, NeoForgeWebSession.cefWheelDelta(-1.0));
        assertEquals(30.0, NeoForgeWebSession.cefWheelDelta(0.25));
    }

    @Test
    void backendSelectionDefaultsToMcefAndIsCaseInsensitive() {
        assertEquals("mcef", NeoForgeWebSession.normalizeBackendSelection(null));
        assertEquals("mcef", NeoForgeWebSession.normalizeBackendSelection("  MCEF "));
        assertEquals("direct-cef", NeoForgeWebSession.normalizeBackendSelection(" Direct-CEF "));
    }

    @Test
    void directBackendFailsExplicitlyWhenConfigurationIsIncomplete() {
        assertThrows(IllegalStateException.class, () ->
                NeoForgeWebSession.validateDirectBackendConfiguration("Windows 11", "", "runtime", "helper"));
        assertThrows(IllegalStateException.class, () ->
                NeoForgeWebSession.validateDirectBackendConfiguration("Linux", "http://127.0.0.1:1/", "runtime", "helper"));
        assertThrows(IllegalStateException.class, () ->
                NeoForgeWebSession.validateDirectBackendConfiguration("Windows 11", "http://127.0.0.1:1/", "", "helper"));
    }

    @Test
    void directBackendConfigurationAcceptsCompleteWindowsProofSelection() {
        NeoForgeWebSession.validateDirectBackendConfiguration(
                "Windows 11", "http://127.0.0.1:8765/", "cef-runtime", "cef-runtime/mcwebui-cef-helper.exe");
    }

    @Test
    void directBridgeRejectsNonLoopbackOrigins() {
        assertThrows(IllegalStateException.class, () ->
                NeoForgeWebSession.validateDirectBridgeUrl("https://example.com/playground"));
        assertDoesNotThrow(() ->
                NeoForgeWebSession.validateDirectBridgeUrl("http://127.0.0.1:18765/?view=direct-cef"));
    }

    @Test
    void directBackendSelectionIsIndependentOfMcefReadiness() {
        assertTrue(NeoForgeBackendSelection.directCefSelected(" direct-cef "));
        assertFalse(NeoForgeBackendSelection.directCefSelected("mcef"));
        assertFalse(NeoForgeBackendSelection.directCefSelected(null));
    }

    @Test
    void warmSessionOnlyOpensAfterARealBrowserTextureExists() {
        assertFalse(NeoForgeBackendSelection.shouldOpenWarmSession(true, false, false, false));
        assertFalse(NeoForgeBackendSelection.shouldOpenWarmSession(true, true, false, false));
        assertFalse(NeoForgeBackendSelection.shouldOpenWarmSession(true, true, true, true));
        assertFalse(NeoForgeBackendSelection.shouldOpenWarmSession(false, true, true, false));
        assertTrue(NeoForgeBackendSelection.shouldOpenWarmSession(true, true, true, false));
    }
}
