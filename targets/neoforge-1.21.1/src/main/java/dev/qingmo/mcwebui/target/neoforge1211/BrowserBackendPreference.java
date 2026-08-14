package dev.qingmo.mcwebui.target.neoforge1211;

import java.util.Locale;

/** Persisted client preference. Keep this target-local so common API stays loader-neutral. */
enum BrowserBackendPreference {
    MCEF,
    DIRECT_CEF,
    AUTO;

    static BrowserBackendPreference parse(String value) {
        if (value == null || value.isBlank()) return MCEF;
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "mcef" -> MCEF;
            case "direct-cef", "directcef" -> DIRECT_CEF;
            case "auto" -> AUTO;
            default -> throw new IllegalArgumentException("Unknown MCWebUI browser backend: " + value);
        };
    }

    String externalName() {
        return switch (this) {
            case MCEF -> "mcef";
            case DIRECT_CEF -> "direct-cef";
            case AUTO -> "auto";
        };
    }
}
