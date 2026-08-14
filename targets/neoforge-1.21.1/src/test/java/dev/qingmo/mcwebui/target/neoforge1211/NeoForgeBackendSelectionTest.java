package dev.qingmo.mcwebui.target.neoforge1211;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NeoForgeBackendSelectionTest {
    @Test void propertyOverridesPersistentConfig() {
        assertEquals(BrowserBackendPreference.DIRECT_CEF,
                NeoForgeBackendSelection.preference(" direct-cef ", BrowserBackendPreference.MCEF));
        assertEquals(BrowserBackendPreference.AUTO,
                NeoForgeBackendSelection.preference("", BrowserBackendPreference.AUTO));
        assertThrows(IllegalArgumentException.class,
                () -> NeoForgeBackendSelection.preference("invented", BrowserBackendPreference.MCEF));
    }

    @Test void explicitMcefNeverSilentlyFallsBack() {
        var missing = NeoForgeBackendSelection.resolve(BrowserBackendPreference.MCEF, false, true);
        assertEquals(ResolvedBrowserBackend.MCEF, missing.backend());
        assertEquals(BackendAvailability.MISSING_DEPENDENCY, missing.availability());
    }

    @Test void explicitDirectNeverFallsBackToMcef() {
        var unsupported = NeoForgeBackendSelection.resolve(BrowserBackendPreference.DIRECT_CEF, false, false);
        assertEquals(ResolvedBrowserBackend.DIRECT_CEF, unsupported.backend());
        assertEquals(BackendAvailability.UNSUPPORTED_PLATFORM, unsupported.availability());

        var conflict = NeoForgeBackendSelection.resolve(BrowserBackendPreference.DIRECT_CEF, true, true);
        assertEquals(ResolvedBrowserBackend.DIRECT_CEF, conflict.backend());
        assertEquals(BackendAvailability.INITIALIZATION_FAILED, conflict.availability());
        assertTrue(conflict.reason().contains("Remove MCEF"));
    }

    @Test void autoConservativelyPrefersMcefThenEligibleDirect() {
        var mcef = NeoForgeBackendSelection.resolve(BrowserBackendPreference.AUTO, true, true);
        assertEquals(ResolvedBrowserBackend.MCEF, mcef.backend());
        assertEquals(BackendAvailability.AVAILABLE, mcef.availability());

        var direct = NeoForgeBackendSelection.resolve(BrowserBackendPreference.AUTO, false, true);
        assertEquals(ResolvedBrowserBackend.DIRECT_CEF, direct.backend());
        assertEquals(BackendAvailability.AVAILABLE, direct.availability());

        var none = NeoForgeBackendSelection.resolve(BrowserBackendPreference.AUTO, false, false);
        assertEquals(ResolvedBrowserBackend.NONE, none.backend());
        assertNotEquals(BackendAvailability.AVAILABLE, none.availability());
    }
}
