package dev.qingmo.mcwebui.nativecef;

/**
 * Immutable snapshot of an offline Direct CEF runtime package import.
 * UI threads must never observe a half-written progress object; a new snapshot
 * is published per meaningful step (file boundaries), not per byte.
 */
public record RuntimeImportProgress(Phase phase, String message, String currentFile,
                                    long filesCompleted, long filesTotal,
                                    long bytesExtracted, long bytesExpected) {

    public enum Phase {
        VALIDATING_PACKAGE,
        WAITING_FOR_LOCK,
        EXTRACTING,
        VALIDATING_RUNTIME,
        PUBLISHING,
        COMPLETE,
        FAILED,
        CANCELLED
    }

    public RuntimeImportProgress {
        phase = phase == null ? Phase.VALIDATING_PACKAGE : phase;
        message = message == null ? "" : message;
        if (filesCompleted < 0) filesCompleted = 0;
        if (filesTotal < 0) filesTotal = 0;
        if (bytesExtracted < 0) bytesExtracted = 0;
        if (bytesExpected < 0) bytesExpected = 0;
    }

    static RuntimeImportProgress simple(Phase phase, String message) {
        return new RuntimeImportProgress(phase, message, null, 0, 0, 0, 0);
    }
}
