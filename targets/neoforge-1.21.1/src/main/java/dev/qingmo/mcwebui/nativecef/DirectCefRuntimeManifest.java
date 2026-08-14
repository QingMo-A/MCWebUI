package dev.qingmo.mcwebui.nativecef;

import java.util.List;
import java.util.Objects;

/** Parsed runtime.json model. Paths are manifest-relative POSIX-style strings. */
public record DirectCefRuntimeManifest(int schemaVersion, int mcwebuiRuntimeAbi,
                                       String runtimeId, String cefVersion,
                                       String chromiumVersion, String platform,
                                       String arch, EntryPoints entrypoints,
                                       List<FileEntry> files) {
    public DirectCefRuntimeManifest {
        runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
        cefVersion = Objects.requireNonNull(cefVersion, "cefVersion");
        chromiumVersion = Objects.requireNonNull(chromiumVersion, "chromiumVersion");
        platform = Objects.requireNonNull(platform, "platform");
        arch = Objects.requireNonNull(arch, "arch");
        entrypoints = Objects.requireNonNull(entrypoints, "entrypoints");
        files = List.copyOf(Objects.requireNonNull(files, "files"));
    }

    public DirectCefRuntimeRequirement identity() {
        return new DirectCefRuntimeRequirement(schemaVersion, mcwebuiRuntimeAbi, runtimeId,
                cefVersion, chromiumVersion, platform, arch);
    }

    public record EntryPoints(String nativePath, String helperPath, String cefPath,
                              String chromeElfPath) {
        public EntryPoints {
            nativePath = Objects.requireNonNull(nativePath, "nativePath");
            helperPath = Objects.requireNonNull(helperPath, "helperPath");
            cefPath = Objects.requireNonNull(cefPath, "cefPath");
            chromeElfPath = Objects.requireNonNull(chromeElfPath, "chromeElfPath");
        }
    }

    public record FileEntry(String path, long size, String sha256) {
        public FileEntry {
            path = Objects.requireNonNull(path, "path");
            sha256 = Objects.requireNonNull(sha256, "sha256");
        }
    }
}
