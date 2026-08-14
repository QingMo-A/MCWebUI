package dev.qingmo.mcwebui.nativecef;

import java.nio.file.Path;

/**
 * Small build-time entry point used by the NeoForge Gradle embedding task.
 * Keeping the call in Java guarantees that an embedded descriptor uses the
 * same parser and identity checks as the runtime catalog.
 */
public final class DirectCefRuntimeReleaseDescriptorBuildValidator {
    private DirectCefRuntimeReleaseDescriptorBuildValidator() { }

    public static void main(String[] args) {
        if (args == null || args.length != 1 || args[0].isBlank()) {
            throw new IllegalArgumentException(
                    "expected one absolute path to a Direct CEF release descriptor");
        }
        Path path = Path.of(args[0]).toAbsolutePath().normalize();
        try {
            DirectCefRuntimeReleaseCatalog.parseAndValidate(path);
        } catch (DirectCefRuntimeException failure) {
            throw new IllegalStateException(
                    "BUILD/RELEASE CONFIG ERROR: Direct CEF release descriptor is invalid: "
                            + failure.getMessage(), failure);
        }
    }
}
