package dev.qingmo.mcwebui.nativecef;

/** Immutable, screen-visible snapshot for a runtime download/install job. */
public record RuntimeDownloadProgress(Phase phase, String message, long bytesDownloaded,
                                      long bytesTotal, RuntimeImportProgress importProgress) {
    public enum Phase { CONNECTING, DOWNLOADING, VERIFYING, INSTALLING, COMPLETE, FAILED, CANCELLED }

    public RuntimeDownloadProgress {
        phase = phase == null ? Phase.CONNECTING : phase;
        message = message == null ? "" : message;
        if (bytesDownloaded < 0) bytesDownloaded = 0;
        if (bytesTotal < 0) bytesTotal = 0;
    }

    public static RuntimeDownloadProgress simple(Phase phase, String message, long total) {
        return new RuntimeDownloadProgress(phase, message, 0, total, null);
    }

    public static RuntimeDownloadProgress installing(RuntimeImportProgress progress, long total) {
        return new RuntimeDownloadProgress(Phase.INSTALLING,
                progress == null ? "Installing runtime" : progress.message(),
                progress == null ? 0 : progress.bytesExtracted(), total, progress);
    }
}
