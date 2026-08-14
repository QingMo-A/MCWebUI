package dev.qingmo.mcwebui.nativecef;

import java.nio.file.Path;

/** Structured result from a runtime download and its Phase B installation. */
public record RuntimeDownloadResult(Status status, Path packageFile,
                                    RuntimeImportResult importResult,
                                    DirectCefRuntimeException failure) {
    public enum Status { INSTALLED, ALREADY_INSTALLED, FAILED, CANCELLED }
    public boolean success() { return status == Status.INSTALLED || status == Status.ALREADY_INSTALLED; }
}
