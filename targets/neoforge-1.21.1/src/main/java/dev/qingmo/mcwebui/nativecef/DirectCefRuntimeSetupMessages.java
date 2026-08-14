package dev.qingmo.mcwebui.nativecef;

/**
 * Player-facing text for Direct CEF setup failures. Minecraft screens use these
 * short messages; full diagnostics stay in the log. The mapping is deliberately
 * Java-only so it can be unit-tested without a Minecraft client.
 */
public final class DirectCefRuntimeSetupMessages {
    private DirectCefRuntimeSetupMessages() { }

    public static String messageFor(DirectCefRuntimeFailureReason reason) {
        if (reason == null) return "The Direct CEF runtime is unavailable.";
        return switch (reason) {
            case NOT_FOUND -> "Runtime is not installed.";
            case MANIFEST_MISSING -> "Runtime files are missing or incomplete.";
            case MANIFEST_INVALID -> "The runtime manifest is invalid.";
            case UNSUPPORTED_SCHEMA -> "The runtime uses an unsupported manifest schema.";
            case WRONG_RUNTIME_ID -> "This package is for a different MCWebUI runtime.";
            case WRONG_ABI -> "This package uses an incompatible MCWebUI runtime ABI.";
            case WRONG_CEF_VERSION -> "This package bundles a different CEF version.";
            case WRONG_CHROMIUM_VERSION -> "This package bundles a different Chromium version.";
            case WRONG_PLATFORM -> "Runtime package targets a different operating system.";
            case WRONG_ARCH -> "Runtime package targets a different CPU architecture.";
            case INVALID_ENTRYPOINT -> "The runtime manifest entrypoints are invalid.";
            case MISSING_FILE -> "Runtime files are missing or incomplete.";
            case FILE_SIZE_MISMATCH -> "Runtime files are corrupted.";
            case HASH_MISMATCH -> "Runtime files are corrupted.";
            case UNEXPECTED_FILE -> "The runtime directory contains unexpected files.";
            case UNSAFE_PATH -> "The runtime package contains unsafe paths.";
            case SYMLINK_NOT_ALLOWED -> "The runtime directory contains symbolic links.";
            case IO_ERROR -> "The runtime could not be accessed (I/O error).";
            case INVALID_ARGUMENT -> "The runtime setup request is invalid.";
            case NATIVE_LOAD_FAILED -> "The Direct CEF native runtime failed to load.";
            case CONFLICTING_RUNTIME -> "A different Direct CEF runtime is already in use.";
            case PACKAGE_INVALID -> "The selected file is not a valid MCWebUI runtime package.";
            case PACKAGE_HASH_MISMATCH -> "The runtime package does not match its expected checksum.";
            case INSTALL_IN_PROGRESS -> "Another process is already installing this runtime.";
            case RUNTIME_IN_USE -> "Runtime is currently in use by another process/session.";
            case INSTALL_CANCELLED -> "Installation was cancelled.";
            case RELEASE_DESCRIPTOR_INVALID -> "Automatic download metadata is invalid or unavailable.";
            case DOWNLOAD_INVALID_URI -> "Automatic download is not configured for this build.";
            case DOWNLOAD_HTTP_ERROR -> "The runtime download server returned an error.";
            case DOWNLOAD_REDIRECT_REJECTED -> "The runtime download redirect was not secure.";
            case DOWNLOAD_TIMEOUT -> "The runtime download timed out.";
            case DOWNLOAD_SIZE_MISMATCH -> "The downloaded runtime size did not match its release metadata.";
            case DOWNLOAD_HASH_MISMATCH -> "The downloaded runtime checksum did not match its release metadata.";
            case INSUFFICIENT_DISK_SPACE -> "There is not enough disk space for the runtime download.";
            case DOWNLOAD_IO_ERROR -> "The runtime download could not be saved.";
            case DOWNLOAD_DNS_ERROR -> "The runtime download host could not be resolved.";
            case DOWNLOAD_CONNECT_ERROR -> "The runtime download server could not be reached.";
            case DOWNLOAD_TLS_ERROR -> "The runtime download TLS connection failed.";
            case DOWNLOAD_WRITE_ERROR -> "The runtime download could not be written to disk.";
            case DOWNLOAD_CANCELLED -> "Runtime download was cancelled.";
            case DOWNLOAD_SOURCE_UNAVAILABLE -> "Automatic download is not configured for this build.";
        };
    }

    /**
     * Whether importing a package can plausibly resolve this failure. Importing
     * cannot fix an unsupported platform/architecture, an ABI/schema mismatch,
     * or a runtime that is currently loaded, so the Import button is disabled
     * for those reasons instead of letting the player retry a package forever.
     */
    public static boolean canImport(DirectCefRuntimeFailureReason reason) {
        if (reason == null) return true;
        return switch (reason) {
            case WRONG_PLATFORM, WRONG_ARCH, UNSUPPORTED_SCHEMA, WRONG_ABI,
                    RUNTIME_IN_USE, INSTALL_IN_PROGRESS, CONFLICTING_RUNTIME,
                    NATIVE_LOAD_FAILED -> false;
            default -> true;
        };
    }
}
