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

    @Test void directAndAutoWaitForGraphicsAndRejectUnsupportedInterop() {
        var eligible = DirectCefStaticCompatibility.evaluate("Windows 11", "amd64");
        var pending = DirectCefGraphicsCompatibility.Result.notProbed("waiting");
        var unsupported = DirectCefGraphicsCompatibility.evaluate(true, true, false, true,
                "Vendor", "Renderer", "4.6", null);

        var directPending = NeoForgeBackendSelection.resolve(
                BrowserBackendPreference.DIRECT_CEF, false, eligible, pending);
        assertEquals(ResolvedBrowserBackend.DIRECT_CEF, directPending.backend());
        assertEquals(BackendAvailability.COMPATIBILITY_PENDING, directPending.availability());

        var directUnsupported = NeoForgeBackendSelection.resolve(
                BrowserBackendPreference.DIRECT_CEF, false, eligible, unsupported);
        assertEquals(ResolvedBrowserBackend.DIRECT_CEF, directUnsupported.backend());
        assertEquals(BackendAvailability.UNSUPPORTED_GRAPHICS, directUnsupported.availability());

        var autoUnsupported = NeoForgeBackendSelection.resolve(
                BrowserBackendPreference.AUTO, false, eligible, unsupported);
        assertEquals(ResolvedBrowserBackend.NONE, autoUnsupported.backend());
        assertEquals(BackendAvailability.UNSUPPORTED_GRAPHICS, autoUnsupported.availability());
    }

    @Test void runtimeSetupIsOfferedOnlyAfterSupportedGraphics() {
        assertTrue(NeoForgeBackendSelection.shouldOfferRuntimeSetup(
                BackendAvailability.RUNTIME_MISSING, DirectCefGraphicsCompatibility.Status.SUPPORTED));
        assertFalse(NeoForgeBackendSelection.shouldOfferRuntimeSetup(
                BackendAvailability.RUNTIME_MISSING, DirectCefGraphicsCompatibility.Status.UNSUPPORTED));
        assertFalse(NeoForgeBackendSelection.shouldOfferRuntimeSetup(
                BackendAvailability.UNSUPPORTED_GRAPHICS, DirectCefGraphicsCompatibility.Status.SUPPORTED));
    }
}
