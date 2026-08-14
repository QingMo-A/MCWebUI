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
                    : windows
                    ? available(preference, ResolvedBrowserBackend.DIRECT_CEF)
                    : unavailable(preference, ResolvedBrowserBackend.DIRECT_CEF,
                    BackendAvailability.UNSUPPORTED_PLATFORM,
                    "Direct CEF currently requires 64-bit Windows.");
            case AUTO -> mcefInstalled
                    ? available(preference, ResolvedBrowserBackend.MCEF)
                    : windows
                    ? available(preference, ResolvedBrowserBackend.DIRECT_CEF)
                    : unavailable(preference, ResolvedBrowserBackend.NONE,
                    BackendAvailability.MISSING_DEPENDENCY,
                    "No supported browser backend is available. Install MCEF.");
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
}
