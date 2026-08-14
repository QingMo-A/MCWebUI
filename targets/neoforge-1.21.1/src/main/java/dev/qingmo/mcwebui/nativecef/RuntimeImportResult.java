package dev.qingmo.mcwebui.nativecef;

import java.nio.file.Path;

/**
 * Structured outcome of {@link DirectCefRuntimePackageImporter#importPackage}.
 * The importer never throws for expected user/package failures: it returns a
 * FAILED/CANCELLED/INSTALL_IN_PROGRESS result whose {@code failure} carries the
 * typed {@link DirectCefRuntimeFailureReason} and the involved paths.
 */
public record RuntimeImportResult(Status status, ValidatedDirectCefRuntime runtime,
                                  DirectCefRuntimeException failure, Path packageFile,
                                  Path stagingDirectory, Path finalDirectory) {

    public enum Status {
        /** The package was extracted, validated, published and rediscovered through the standard path. */
        INSTALLED,
        /** A valid runtime already existed at the standard location; nothing was modified. */
        ALREADY_INSTALLED,
        /** Another process holds the installation lock; wait and retry. */
        INSTALL_IN_PROGRESS,
        /** The caller cancelled the import; staging was cleaned up, the final runtime was not touched. */
        CANCELLED,
        /** The import failed; {@code failure} carries the typed reason and paths. */
        FAILED
    }

    public boolean success() {
        return status == Status.INSTALLED || status == Status.ALREADY_INSTALLED;
    }
}
