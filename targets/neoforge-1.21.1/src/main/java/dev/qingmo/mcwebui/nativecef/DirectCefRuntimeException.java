package dev.qingmo.mcwebui.nativecef;

import java.nio.file.Path;

/** A Direct CEF setup failure with a stable reason suitable for UI/runner reporting. */
public final class DirectCefRuntimeException extends IllegalStateException {
    private final DirectCefRuntimeFailureReason reason;
    private final Path path;

    public DirectCefRuntimeException(DirectCefRuntimeFailureReason reason, String message) {
        this(reason, message, null, null);
    }

    public DirectCefRuntimeException(DirectCefRuntimeFailureReason reason, String message, Throwable cause) {
        this(reason, message, cause, null);
    }

    public DirectCefRuntimeException(DirectCefRuntimeFailureReason reason, String message, Path path) {
        this(reason, message, null, path);
    }

    public DirectCefRuntimeException(DirectCefRuntimeFailureReason reason, String message,
                                     Throwable cause, Path path) {
        super(message, cause);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
        this.path = path;
    }

    public DirectCefRuntimeFailureReason reason() { return reason; }
    public Path path() { return path; }
}
