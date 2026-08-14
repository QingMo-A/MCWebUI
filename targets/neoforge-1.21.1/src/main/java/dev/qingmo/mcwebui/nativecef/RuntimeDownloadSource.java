package dev.qingmo.mcwebui.nativecef;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/** Injectable transport for deterministic downloader tests. */
@FunctionalInterface
public interface RuntimeDownloadSource {
    DownloadResponse open(URI uri, DirectCefRuntimePackageImporter.CancellationToken cancellation)
            throws IOException, InterruptedException;

    record DownloadResponse(InputStream body, long contentLength, URI effectiveUri) implements AutoCloseable {
        public DownloadResponse {
            if (body == null) throw new IllegalArgumentException("download response body must not be null");
            if (contentLength < -1) throw new IllegalArgumentException("download response content length is invalid");
        }
        public DownloadResponse(InputStream body, long contentLength) { this(body, contentLength, null); }
        @Override public void close() throws IOException { body.close(); }
    }
}
