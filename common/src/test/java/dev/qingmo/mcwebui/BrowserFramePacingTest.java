package dev.qingmo.mcwebui;

import dev.qingmo.mcwebui.backend.BrowserFramePacing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserFramePacingTest {
    @Test
    void unsupportedBackendUsesSafeDefaults() {
        BrowserFramePacing pacing = new BrowserFramePacing() { };
        assertFalse(pacing.supportsExternalFrames());
        assertFalse(pacing.requestExternalFrame());
    }

    @Test
    void backendCanOptIntoOneNonBlockingRequest() {
        BrowserFramePacing pacing = new BrowserFramePacing() {
            @Override public boolean supportsExternalFrames() { return true; }
            @Override public boolean requestExternalFrame() { return true; }
        };
        assertTrue(pacing.supportsExternalFrames());
        assertTrue(pacing.requestExternalFrame());
    }
}
