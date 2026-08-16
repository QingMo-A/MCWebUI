package dev.qingmo.mcwebui.target.neoforge1211;

/** Pure selection helpers kept free of Minecraft static initialization for tests. */
final class NeoForgeBackendSelection {
    private NeoForgeBackendSelection() { }

    record Resolution(BrowserBackendPreference preference, ResolvedBrowserBackend backend,
                      BackendAvailability availability, String reason) { }

    static BrowserBackendPreference preference(String propertyValue, BrowserBackendPreference configValue) {
        return propertyValue == null || propertyValue.isBlank()
                ? configValue : BrowserBackendPreference.parse(propertyValue);
    }

    static Resolution resolve(BrowserBackendPreference preference, boolean mcefInstalled, boolean windows) {
        DirectCefStaticCompatibility.Result staticCompatibility = DirectCefStaticCompatibility.evaluate(
                windows ? "Windows" : "Other", "amd64");
        DirectCefGraphicsCompatibility.Result graphicsCompatibility =
                DirectCefGraphicsCompatibility.evaluate(true, true, true, true, "", "", "", null);
        return resolve(preference, mcefInstalled, staticCompatibility, graphicsCompatibility);
    }

    static Resolution resolve(BrowserBackendPreference preference, boolean mcefInstalled,
                              DirectCefStaticCompatibility.Result staticCompatibility,
                              DirectCefGraphicsCompatibility.Result graphicsCompatibility) {
        return switch (preference) {
            case MCEF -> mcefInstalled
                    ? available(preference, ResolvedBrowserBackend.MCEF)
                    : unavailable(preference, ResolvedBrowserBackend.MCEF,
                    BackendAvailability.MISSING_DEPENDENCY,
                    "CinemaMod MCEF is not installed. Install MCEF or select Direct CEF/AUTO.");
            case DIRECT_CEF -> mcefInstalled
                    ? unavailable(preference, ResolvedBrowserBackend.DIRECT_CEF,
                    BackendAvailability.INITIALIZATION_FAILED,
                    "Direct CEF cannot start while the MCEF mod is loaded. Remove MCEF and restart to avoid loading two CEF runtimes.")
                    : resolveDirect(preference, staticCompatibility, graphicsCompatibility);
            case AUTO -> mcefInstalled
                    ? available(preference, ResolvedBrowserBackend.MCEF)
                    : resolveDirect(preference, staticCompatibility, graphicsCompatibility);
        };
    }

    private static Resolution resolveDirect(BrowserBackendPreference preference,
                                            DirectCefStaticCompatibility.Result staticCompatibility,
                                            DirectCefGraphicsCompatibility.Result graphicsCompatibility) {
        if (staticCompatibility.status() == DirectCefStaticCompatibility.Status.UNSUPPORTED_PLATFORM) {
            return unavailable(preference, preference == BrowserBackendPreference.AUTO
                            ? ResolvedBrowserBackend.NONE : ResolvedBrowserBackend.DIRECT_CEF,
                    BackendAvailability.UNSUPPORTED_PLATFORM, staticCompatibility.reason());
        }
        if (staticCompatibility.status() == DirectCefStaticCompatibility.Status.UNSUPPORTED_ARCHITECTURE) {
            return unavailable(preference, preference == BrowserBackendPreference.AUTO
                            ? ResolvedBrowserBackend.NONE : ResolvedBrowserBackend.DIRECT_CEF,
                    BackendAvailability.UNSUPPORTED_ARCHITECTURE, staticCompatibility.reason());
        }
        return switch (graphicsCompatibility.status()) {
            case SUPPORTED -> available(preference, ResolvedBrowserBackend.DIRECT_CEF);
            case NOT_PROBED -> unavailable(preference, ResolvedBrowserBackend.DIRECT_CEF,
                    BackendAvailability.COMPATIBILITY_PENDING, graphicsCompatibility.reason());
            case UNSUPPORTED, PROBE_FAILED -> unavailable(preference,
                    preference == BrowserBackendPreference.AUTO
                            ? ResolvedBrowserBackend.NONE : ResolvedBrowserBackend.DIRECT_CEF,
                    BackendAvailability.UNSUPPORTED_GRAPHICS, graphicsCompatibility.reason());
        };
    }

    private static Resolution available(BrowserBackendPreference preference, ResolvedBrowserBackend backend) {
        return new Resolution(preference, backend, BackendAvailability.AVAILABLE, "");
    }

    private static Resolution unavailable(BrowserBackendPreference preference, ResolvedBrowserBackend backend,
                                          BackendAvailability availability, String reason) {
        return new Resolution(preference, backend, availability, reason);
    }

    static boolean directCefSelected(String selection) {
        return selection != null && BrowserBackendPreference.parse(selection) == BrowserBackendPreference.DIRECT_CEF;
    }

    static boolean shouldOpenWarmSession(boolean requested, boolean sessionPresent,
                                         boolean renderableFrame, boolean alreadyOpen) {
        return requested && sessionPresent && renderableFrame && !alreadyOpen;
    }

    static boolean shouldOfferRuntimeSetup(BackendAvailability availability,
                                           DirectCefGraphicsCompatibility.Status graphicsStatus) {
        return availability == BackendAvailability.RUNTIME_MISSING
                && graphicsStatus == DirectCefGraphicsCompatibility.Status.SUPPORTED;
    }
}
