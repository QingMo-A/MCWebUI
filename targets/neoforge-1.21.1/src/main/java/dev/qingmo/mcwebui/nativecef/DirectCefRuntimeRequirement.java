package dev.qingmo.mcwebui.nativecef;

import java.util.Locale;
import java.util.Objects;

/** Immutable identity required by the NeoForge Direct CEF backend. */
public record DirectCefRuntimeRequirement(int schemaVersion, int mcwebuiRuntimeAbi,
                                          String runtimeId, String cefVersion,
                                          String chromiumVersion, String platform,
                                          String arch) {
    public static final int SCHEMA_VERSION = 1;
    public static final int RUNTIME_ABI = 1;
    public static final String RUNTIME_ID = "cef-144.0.33-cb4715c";
    public static final String CEF_VERSION = "144.0.33";
    public static final String CHROMIUM_VERSION = "144.0.7559.259";
    public static final String PLATFORM = "windows";
    public static final String ARCH = "x86_64";

    public DirectCefRuntimeRequirement {
        runtimeId = requireNonBlank(runtimeId, "runtimeId");
        cefVersion = requireNonBlank(cefVersion, "cefVersion");
        chromiumVersion = requireNonBlank(chromiumVersion, "chromiumVersion");
        platform = requireNonBlank(platform, "platform").toLowerCase(Locale.ROOT);
        arch = requireNonBlank(arch, "arch").toLowerCase(Locale.ROOT);
    }

    public static DirectCefRuntimeRequirement required() {
        return new DirectCefRuntimeRequirement(SCHEMA_VERSION, RUNTIME_ABI, RUNTIME_ID,
                CEF_VERSION, CHROMIUM_VERSION, PLATFORM, ARCH);
    }

    public boolean sameIdentity(DirectCefRuntimeRequirement other) {
        return other != null
                && schemaVersion == other.schemaVersion
                && mcwebuiRuntimeAbi == other.mcwebuiRuntimeAbi
                && runtimeId.equalsIgnoreCase(other.runtimeId)
                && cefVersion.equalsIgnoreCase(other.cefVersion)
                && chromiumVersion.equalsIgnoreCase(other.chromiumVersion)
                && platform.equalsIgnoreCase(other.platform)
                && arch.equalsIgnoreCase(other.arch);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
