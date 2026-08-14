package dev.qingmo.mcwebui.nativecef;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads the MCWebUI-owned Direct CEF release pin from the mod resources.
 *
 * <p>The catalog is deliberately a local trust anchor. It never performs a
 * network request and it does not invent a fallback URL when the project has
 * not configured a release asset yet. A missing resource therefore means that
 * automatic download is not configured, while a present but invalid resource
 * is a release/build configuration error.</p>
 */
public final class DirectCefRuntimeReleaseCatalog {
    public static final String RESOURCE_PATH = "META-INF/mcwebui/direct-cef-runtime-release.json";
    private static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;

    private DirectCefRuntimeReleaseCatalog() { }

    /** Load the descriptor bundled in the running MCWebUI class path. */
    public static Optional<DirectCefRuntimeReleaseDescriptor> current() {
        return current(DirectCefRuntimeReleaseCatalog.class.getClassLoader(),
                DirectCefRuntimeRequirement.required());
    }

    /** Load the descriptor bundled in the running MCWebUI class path. */
    public static Optional<DirectCefRuntimeReleaseDescriptor> current(
            DirectCefRuntimeRequirement expected) {
        return current(DirectCefRuntimeReleaseCatalog.class.getClassLoader(), expected);
    }

    /**
     * Testable class-loader variant of {@link #current()}. A null loader uses
     * this class's loader, matching ordinary production class-path lookup.
     */
    public static Optional<DirectCefRuntimeReleaseDescriptor> current(ClassLoader loader) {
        return current(loader, DirectCefRuntimeRequirement.required());
    }

    /** Testable class-loader variant with an explicit required runtime identity. */
    public static Optional<DirectCefRuntimeReleaseDescriptor> current(
            ClassLoader loader, DirectCefRuntimeRequirement expected) {
        ClassLoader effectiveLoader = loader == null
                ? DirectCefRuntimeReleaseCatalog.class.getClassLoader() : loader;
        DirectCefRuntimeRequirement required = required(expected);
        final InputStream stream;
        try {
            stream = effectiveLoader == null ? ClassLoader.getSystemResourceAsStream(RESOURCE_PATH)
                    : effectiveLoader.getResourceAsStream(RESOURCE_PATH);
        } catch (RuntimeException ex) {
            throw configurationError("resource lookup failed", ex);
        }
        if (stream == null) return Optional.empty();
        try (InputStream input = stream) {
            byte[] bytes = input.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
            if (bytes.length > MAX_DESCRIPTOR_BYTES) {
                throw invalid("bundled release descriptor exceeds the 1 MiB safety bound");
            }
            String source = new String(bytes, StandardCharsets.UTF_8);
            DirectCefRuntimeReleaseDescriptor descriptor = parseCatalogDescriptor(source, required);
            return Optional.of(descriptor);
        } catch (DirectCefRuntimeException ex) {
            throw configurationError("resource " + RESOURCE_PATH, ex);
        } catch (IOException | RuntimeException ex) {
            throw configurationError("resource " + RESOURCE_PATH, ex);
        }
    }

    /**
     * Parse and strictly validate a descriptor supplied to the NeoForge build
     * embedding property. Unlike a catalog load, this path requires a
     * configured HTTPS download source because it is intended for production
     * automatic download.
     */
    public static DirectCefRuntimeReleaseDescriptor parseAndValidate(Path path) {
        return parseAndValidate(path, DirectCefRuntimeRequirement.required());
    }

    /** Shared parse/validation entry point used by the catalog and Gradle embedding. */
    public static DirectCefRuntimeReleaseDescriptor parseAndValidate(
            Path path, DirectCefRuntimeRequirement expected) {
        if (path == null) throw invalid("descriptor path must not be null");
        DirectCefRuntimeRequirement required = required(expected);
        DirectCefRuntimeReleaseDescriptor descriptor = DirectCefRuntimeReleaseDescriptor.parse(path);
        descriptor.validateFor(required);
        return descriptor;
    }

    private static DirectCefRuntimeReleaseDescriptor parseCatalogDescriptor(
            String source, DirectCefRuntimeRequirement expected) {
        DirectCefRuntimeReleaseDescriptor descriptor = DirectCefRuntimeReleaseDescriptor.parse(source);
        if (descriptor.descriptorVersion() != DirectCefRuntimeReleaseDescriptor.CURRENT_VERSION) {
            throw invalid("unsupported release descriptor version: " + descriptor.descriptorVersion());
        }
        if (!descriptor.requirement().sameIdentity(expected)) {
            throw invalid("release descriptor runtime identity does not match the required Direct CEF runtime");
        }
        // An empty URI is a valid, explicitly unconfigured release candidate.
        // A configured URI has already passed the descriptor's HTTPS checks;
        // validateFor also keeps this path aligned with the downloader contract.
        if (descriptor.sourceConfigured()) descriptor.validateFor(expected);
        return descriptor;
    }

    private static DirectCefRuntimeRequirement required(DirectCefRuntimeRequirement expected) {
        return Objects.requireNonNull(expected, "expected");
    }

    private static DirectCefRuntimeException invalid(String message) {
        return new DirectCefRuntimeException(DirectCefRuntimeFailureReason.RELEASE_DESCRIPTOR_INVALID, message);
    }

    private static DirectCefRuntimeException configurationError(String source, Throwable cause) {
        String detail = cause == null || cause.getMessage() == null ? "unknown descriptor error" : cause.getMessage();
        return new DirectCefRuntimeException(DirectCefRuntimeFailureReason.RELEASE_DESCRIPTOR_INVALID,
                "BUILD/RELEASE CONFIG ERROR: Bundled Direct CEF release descriptor is invalid ("
                        + source + "): " + detail, cause);
    }
}
