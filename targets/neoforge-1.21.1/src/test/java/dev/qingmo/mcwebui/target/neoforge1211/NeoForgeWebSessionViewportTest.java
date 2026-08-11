package dev.qingmo.mcwebui.target.neoforge1211;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Locks the GUI-coordinate contract used by the browser surface and Screen input routing. */
class NeoForgeWebSessionViewportTest {
    @Test
    void browserViewportUsesGuiDimensionsAtHighScale() {
        int guiWidth = 1280;
        int guiHeight = 720;
        double guiScale = 2.0;

        // The scale is intentionally not applied to the CEF viewport: Minecraft's Screen
        // projection scales the rendered quad, while CEF and mouse events share GUI units.
        assertEquals(guiWidth, NeoForgeWebSession.browserDimension(guiWidth, guiScale));
        assertEquals(guiHeight, NeoForgeWebSession.browserDimension(guiHeight, guiScale));
        assertEquals(318.5, NeoForgeWebSession.mapCoordinate(318.5, guiWidth, guiWidth));
    }

    @Test
    void lockedViewportMapsCurrentGuiCoordinatesByRatio() {
        assertEquals(640.0, NeoForgeWebSession.mapCoordinate(960.0, 1920, 1280));
        // Use independent extents so a width-based mapping cannot accidentally pass for y.
        assertEquals(300.0, NeoForgeWebSession.mapCoordinate(450.0, 900, 600));
    }
}
