package dev.qingmo.mcwebui.target.neoforge1211;

import java.util.Locale;

/** Pure OS/architecture eligibility check. It never loads native code. */
final class DirectCefStaticCompatibility {
    enum Status { ELIGIBLE, UNSUPPORTED_PLATFORM, UNSUPPORTED_ARCHITECTURE }
    record Result(Status status, String operatingSystem, String architecture, String reason) {
        boolean eligible() { return status == Status.ELIGIBLE; }
    }

    private DirectCefStaticCompatibility() { }

    static Result evaluate(String osName, String osArch) {
        String os = osName == null ? "unknown" : osName.trim();
        String normalizedArch = normalizeArchitecture(osArch);
        if (!os.toLowerCase(Locale.ROOT).contains("win")) {
            return new Result(Status.UNSUPPORTED_PLATFORM, os, normalizedArch,
                    "Direct CEF currently supports Windows x86_64 only.");
        }
        if (!"x86_64".equals(normalizedArch)) {
            return new Result(Status.UNSUPPORTED_ARCHITECTURE, os, normalizedArch,
                    "Direct CEF requires Windows x86_64; detected architecture " + normalizedArch + ".");
        }
        return new Result(Status.ELIGIBLE, os, normalizedArch, "");
    }

    static String normalizeArchitecture(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "amd64", "x86-64" -> "x86_64";
            case "arm64", "aarch64" -> "arm64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            default -> value.trim().toLowerCase(Locale.ROOT);
        };
    }
}
