package dev.qingmo.mcwebui.nativecef;

import java.nio.file.Path;
import java.util.Objects;

/** A runtime directory whose manifest, layout and payload hashes have all passed validation. */
public record ValidatedDirectCefRuntime(Path directory, DirectCefRuntimeManifest manifest, Source source) {
    public enum Source { STANDARD, OVERRIDE }

    public ValidatedDirectCefRuntime(Path directory, DirectCefRuntimeManifest manifest) {
        this(directory, manifest, Source.STANDARD);
    }

    public ValidatedDirectCefRuntime {
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        manifest = Objects.requireNonNull(manifest, "manifest");
        source = Objects.requireNonNull(source, "source");
    }

    public DirectCefRuntimeRequirement identity() { return manifest.identity(); }
    public Path path(String manifestPath) { return directory.resolve(manifestPath.replace('/', java.io.File.separatorChar)); }
    public Path nativeLibrary() { return path(manifest.entrypoints().nativePath()); }
    public Path helperExecutable() { return path(manifest.entrypoints().helperPath()); }
    public Path cefLibrary() { return path(manifest.entrypoints().cefPath()); }
    public Path chromeElfLibrary() { return path(manifest.entrypoints().chromeElfPath()); }
}
