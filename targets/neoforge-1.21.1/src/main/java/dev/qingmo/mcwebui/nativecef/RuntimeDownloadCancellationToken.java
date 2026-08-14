package dev.qingmo.mcwebui.nativecef;

/**
 * Optional cancellation extension for a download that currently owns a
 * blocking response stream. Closing the registered resource must be
 * non-blocking; implementations still expose the Phase B cancellation flag.
 */
public interface RuntimeDownloadCancellationToken
        extends DirectCefRuntimePackageImporter.CancellationToken {
    void registerResource(AutoCloseable resource);
    void clearResource(AutoCloseable resource);
}
