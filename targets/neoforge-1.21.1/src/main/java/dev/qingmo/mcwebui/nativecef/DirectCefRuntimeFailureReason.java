package dev.qingmo.mcwebui.nativecef;

/** Machine-readable reasons why a Direct CEF runtime cannot be used. */
public enum DirectCefRuntimeFailureReason {
    NOT_FOUND,
    MANIFEST_MISSING,
    MANIFEST_INVALID,
    UNSUPPORTED_SCHEMA,
    WRONG_RUNTIME_ID,
    WRONG_ABI,
    WRONG_CEF_VERSION,
    WRONG_CHROMIUM_VERSION,
    WRONG_PLATFORM,
    WRONG_ARCH,
    INVALID_ENTRYPOINT,
    MISSING_FILE,
    FILE_SIZE_MISMATCH,
    HASH_MISMATCH,
    UNEXPECTED_FILE,
    UNSAFE_PATH,
    SYMLINK_NOT_ALLOWED,
    IO_ERROR,
    INVALID_ARGUMENT,
    NATIVE_LOAD_FAILED,
    CONFLICTING_RUNTIME
}
