package dev.qingmo.mcwebui.nativecef;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** Pure-Java HTTPS downloader that delegates extraction/publish to Phase B. */
public final class DirectCefRuntimeDownloader {
    public static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L;
    public static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 60_000L;
    public static final long STAGING_MARGIN_BYTES = 16L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final long PROGRESS_INTERVAL_NANOS = 50_000_000L;

    public interface ProgressSink { void onProgress(RuntimeDownloadProgress progress); }

    private final RuntimeDownloadSource source;

    public DirectCefRuntimeDownloader() { this(defaultHttpSource()); }
    public DirectCefRuntimeDownloader(RuntimeDownloadSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }
    public DirectCefRuntimeDownloader(HttpClient client) {
        this(httpSource(Objects.requireNonNull(client, "client")));
    }

    public RuntimeDownloadResult download(DirectCefRuntimeReleaseDescriptor descriptor, Path instanceRoot,
                                          ProgressSink progress, DirectCefRuntimePackageImporter.CancellationToken cancellation) {
        ProgressSink sink = progress == null ? p -> { } : progress;
        DirectCefRuntimePackageImporter.CancellationToken cancel = cancellation == null ? () -> false : cancellation;
        Path root = instanceRoot == null ? null : instanceRoot.toAbsolutePath().normalize();
        Path part = null;
        try {
            if (descriptor == null) throw failure(DirectCefRuntimeFailureReason.RELEASE_DESCRIPTOR_INVALID,
                    "release descriptor must not be null", null);
            descriptor.validateFor(DirectCefRuntimeRequirement.required());
            if (root == null) throw failure(DirectCefRuntimeFailureReason.INVALID_ARGUMENT,
                    "Direct CEF instance root must not be null", null);
            checkCancelled(cancel);
            Path downloadDirectory = root.resolve("mcwebui").resolve("runtime").resolve(".downloads").normalize();
            try {
                Files.createDirectories(downloadDirectory);
            } catch (IOException ex) {
                throw failure(DirectCefRuntimeFailureReason.DOWNLOAD_WRITE_ERROR,
                        "Unable to create runtime download staging directory", ex, downloadDirectory);
            }
            part = downloadDirectory.resolve(descriptor.artifactId() + "." + java.util.UUID.randomUUID() + ".part");
            checkDiskSpace(downloadDirectory, descriptor.packageSize(), descriptor.runtimePayloadSize());
            sink.onProgress(RuntimeDownloadProgress.simple(RuntimeDownloadProgress.Phase.CONNECTING,
                    "Connecting to runtime download", descriptor.packageSize()));
            long downloaded = writeDownload(descriptor, part, sink, cancel);
            checkCancelled(cancel);
            sink.onProgress(new RuntimeDownloadProgress(RuntimeDownloadProgress.Phase.VERIFYING,
                    "Verifying runtime package", downloaded, descriptor.packageSize(), null));
            if (downloaded != descriptor.packageSize()) {
                throw failure(DirectCefRuntimeFailureReason.DOWNLOAD_SIZE_MISMATCH,
                        "Downloaded runtime size mismatch (expected " + descriptor.packageSize()
                                + ", actual " + downloaded + ")", part);
            }
            String actualSha = sha256(part);
            if (!actualSha.equalsIgnoreCase(descriptor.packageSha256())) {
                throw failure(DirectCefRuntimeFailureReason.DOWNLOAD_HASH_MISMATCH,
                        "Downloaded runtime SHA-256 mismatch (expected " + descriptor.packageSha256()
                                + ", actual " + actualSha + ")", part);
            }
            sink.onProgress(RuntimeDownloadProgress.simple(RuntimeDownloadProgress.Phase.INSTALLING,
                    "Installing runtime package", descriptor.packageSize()));
            RuntimeImportResult imported = DirectCefRuntimePackageImporter.importPackage(part, root,
                    new DirectCefRuntimePackageImporter.Options(DirectCefRuntimeRequirement.required(),
                            descriptor.packageSha256(), p -> sink.onProgress(new RuntimeDownloadProgress(
                                    RuntimeDownloadProgress.Phase.INSTALLING, p.message(), descriptor.packageSize(),
                                    descriptor.packageSize(), p)), cancel,
                            DirectCefRuntimePackageImporter.DEFAULT_LOCK_TIMEOUT_MILLIS));
            if (!imported.success()) {
                if (imported.status() == RuntimeImportResult.Status.CANCELLED) {
                    sink.onProgress(RuntimeDownloadProgress.simple(RuntimeDownloadProgress.Phase.CANCELLED,
                            "Runtime download cancelled", descriptor.packageSize()));
                    return new RuntimeDownloadResult(RuntimeDownloadResult.Status.CANCELLED, null, imported,
                            imported.failure());
                }
                DirectCefRuntimeException error = imported.failure() == null
                        ? failure(DirectCefRuntimeFailureReason.IO_ERROR, "Runtime package installation failed", part)
                        : imported.failure();
                throw error;
            }
            sink.onProgress(RuntimeDownloadProgress.simple(RuntimeDownloadProgress.Phase.COMPLETE,
                    "Runtime installed successfully", descriptor.packageSize()));
            return new RuntimeDownloadResult(imported.status() == RuntimeImportResult.Status.ALREADY_INSTALLED
                    ? RuntimeDownloadResult.Status.ALREADY_INSTALLED : RuntimeDownloadResult.Status.INSTALLED,
                    null, imported, null);
        } catch (CancelledException ex) {
            sink.onProgress(RuntimeDownloadProgress.simple(RuntimeDownloadProgress.Phase.CANCELLED,
                    "Runtime download cancelled", descriptor == null ? 0 : descriptor.packageSize()));
            return new RuntimeDownloadResult(RuntimeDownloadResult.Status.CANCELLED, null, null,
                    failure(DirectCefRuntimeFailureReason.DOWNLOAD_CANCELLED, "Runtime download was cancelled", null));
        } catch (DirectCefRuntimeException ex) {
            RuntimeDownloadProgress.Phase phase = ex.reason() == DirectCefRuntimeFailureReason.DOWNLOAD_CANCELLED
                    ? RuntimeDownloadProgress.Phase.CANCELLED : RuntimeDownloadProgress.Phase.FAILED;
            sink.onProgress(RuntimeDownloadProgress.simple(phase, ex.getMessage(), descriptor == null ? 0 : descriptor.packageSize()));
            return new RuntimeDownloadResult(phase == RuntimeDownloadProgress.Phase.CANCELLED
                    ? RuntimeDownloadResult.Status.CANCELLED : RuntimeDownloadResult.Status.FAILED,
                    null, null, ex);
        } catch (RuntimeException ex) {
            DirectCefRuntimeException typed = failure(DirectCefRuntimeFailureReason.DOWNLOAD_SOURCE_UNAVAILABLE,
                    "Runtime download source failed: " + ex.getMessage(), ex, part);
            sink.onProgress(RuntimeDownloadProgress.simple(RuntimeDownloadProgress.Phase.FAILED,
                    typed.getMessage(), descriptor == null ? 0 : descriptor.packageSize()));
            return new RuntimeDownloadResult(RuntimeDownloadResult.Status.FAILED, null, null, typed);
        } catch (IOException ex) {
            DirectCefRuntimeFailureReason reason = transportReason(ex, DirectCefRuntimeFailureReason.DOWNLOAD_IO_ERROR);
            DirectCefRuntimeException typed = failure(reason,
                    "Unable to download runtime package: " + ex.getMessage(), ex, part);
            sink.onProgress(RuntimeDownloadProgress.simple(RuntimeDownloadProgress.Phase.FAILED,
                    typed.getMessage(), descriptor == null ? 0 : descriptor.packageSize()));
            return new RuntimeDownloadResult(RuntimeDownloadResult.Status.FAILED, null, null, typed);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            DirectCefRuntimeException typed = failure(DirectCefRuntimeFailureReason.DOWNLOAD_CANCELLED,
                    "Runtime download was interrupted", ex, part);
            sink.onProgress(RuntimeDownloadProgress.simple(RuntimeDownloadProgress.Phase.CANCELLED,
                    typed.getMessage(), descriptor == null ? 0 : descriptor.packageSize()));
            return new RuntimeDownloadResult(RuntimeDownloadResult.Status.CANCELLED, null, null, typed);
        } finally {
            if (part != null) try { Files.deleteIfExists(part); } catch (IOException ignored) { }
        }
    }

    private long writeDownload(DirectCefRuntimeReleaseDescriptor descriptor, Path part, ProgressSink sink,
                               DirectCefRuntimePackageImporter.CancellationToken cancel)
            throws IOException, InterruptedException {
        RuntimeDownloadSource.DownloadResponse response = source.open(descriptor.downloadUri(), cancel);
        RuntimeDownloadCancellationToken resourceToken = cancel instanceof RuntimeDownloadCancellationToken token
                ? token : null;
        if (resourceToken != null) resourceToken.registerResource(response);
        try (response) {
            if (response.contentLength() >= 0 && response.contentLength() != descriptor.packageSize()) {
                throw failure(DirectCefRuntimeFailureReason.DOWNLOAD_SIZE_MISMATCH,
                        "HTTP content length does not match release descriptor", part);
            }
            long expected = descriptor.packageSize(), total = 0, lastEmit = 0;
            long lastNanos = 0;
            final OutputStream output;
            try {
                output = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (IOException ex) {
                throw failure(DirectCefRuntimeFailureReason.DOWNLOAD_WRITE_ERROR,
                        "Unable to create runtime download staging file", ex, part);
            }
            try (InputStream in = response.body(); OutputStream out = output) {
                byte[] buffer = new byte[64 * 1024];
                while (true) {
                    checkCancelled(cancel);
                    int read = in.read(buffer);
                    if (read < 0) break;
                    if (read == 0) continue;
                    total += read;
                    if (total > expected) throw failure(DirectCefRuntimeFailureReason.DOWNLOAD_SIZE_MISMATCH,
                            "Download exceeded the release descriptor package size", part);
                    try {
                        out.write(buffer, 0, read);
                    } catch (IOException ex) {
                        throw failure(DirectCefRuntimeFailureReason.DOWNLOAD_WRITE_ERROR,
                                "Unable to write runtime download staging file", ex, part);
                    }
                    long now = System.nanoTime();
                    if (lastNanos == 0 || now - lastNanos >= PROGRESS_INTERVAL_NANOS
                            || total - lastEmit >= 256 * 1024L) {
                        sink.onProgress(new RuntimeDownloadProgress(RuntimeDownloadProgress.Phase.DOWNLOADING,
                                "Downloading runtime package", total, expected, null));
                        lastNanos = now; lastEmit = total;
                    }
                }
            }
            return total;
        } catch (DirectCefRuntimeException ex) { throw ex;
        } catch (HttpTimeoutException ex) {
            throw failure(DirectCefRuntimeFailureReason.DOWNLOAD_TIMEOUT,
                    "Runtime download timed out", ex, part);
        } catch (IOException ex) {
            if (cancel.isCancelled()) throw new CancelledException();
            throw ex;
        } finally {
            if (resourceToken != null) resourceToken.clearResource(response);
        }
    }

    static long requiredFreshInstallBytes(long packageSize, long runtimePayloadSize) {
        if (packageSize <= 0 || runtimePayloadSize < 0) {
            throw failure(DirectCefRuntimeFailureReason.INSUFFICIENT_DISK_SPACE,
                    "Runtime package size information is invalid", null);
        }
        // Older descriptor v1 files do not have runtimePayloadSize. Keep them
        // readable, but reserve one additional package-size as a conservative
        // extraction estimate instead of returning to the old ZIP-only model.
        long unpacked = runtimePayloadSize > 0 ? runtimePayloadSize : packageSize;
        try {
            return Math.addExact(Math.addExact(packageSize, unpacked), STAGING_MARGIN_BYTES);
        } catch (ArithmeticException ex) {
            throw failure(DirectCefRuntimeFailureReason.INSUFFICIENT_DISK_SPACE,
                    "Runtime package size is too large", ex, null);
        }
    }

    private static void checkDiskSpace(Path directory, long packageSize, long runtimePayloadSize) {
        try {
            FileStore store = Files.getFileStore(directory);
            long needed = requiredFreshInstallBytes(packageSize, runtimePayloadSize);
            if (store.getUsableSpace() < needed) throw failure(DirectCefRuntimeFailureReason.INSUFFICIENT_DISK_SPACE,
                    "Insufficient disk space for the runtime ZIP, extracted staging, and safety margin"
                            + " (required " + needed + " bytes)", directory);
        } catch (IOException ignored) {
            // Some virtual/launcher filesystems cannot report usable space.
            // This gate is advisory; exact bounded writes still remain authoritative.
        }
    }

    private static String sha256(Path file) throws IOException {
        try { MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) { byte[] buffer = new byte[64 * 1024]; int read; while ((read = in.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read); }
            return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }

    private static void checkCancelled(DirectCefRuntimePackageImporter.CancellationToken cancel) {
        if (cancel.isCancelled()) throw new CancelledException();
    }
    private static DirectCefRuntimeException failure(DirectCefRuntimeFailureReason reason, String message, Path path) { return new DirectCefRuntimeException(reason, message, path); }
    private static DirectCefRuntimeException failure(DirectCefRuntimeFailureReason reason, String message, Throwable cause, Path path) { return new DirectCefRuntimeException(reason, message, cause, path); }
    private static final class CancelledException extends RuntimeException { }

    private static RuntimeDownloadSource defaultHttpSource() {
        return httpSource(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(DEFAULT_CONNECT_TIMEOUT_MILLIS))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    private static RuntimeDownloadSource httpSource(HttpClient client) {
        return (uri, cancellation) -> {
            URI current = validateHttpsUri(uri, DirectCefRuntimeFailureReason.DOWNLOAD_INVALID_URI);
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                if (cancellation.isCancelled()) throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.DOWNLOAD_CANCELLED, "Runtime download was cancelled");
                HttpRequest request = HttpRequest.newBuilder(current).timeout(Duration.ofMillis(DEFAULT_REQUEST_TIMEOUT_MILLIS))
                        .header("Accept", "application/zip, application/octet-stream").GET().build();
                final HttpResponse<InputStream> response;
                try { response = client.send(request, HttpResponse.BodyHandlers.ofInputStream()); }
                catch (HttpTimeoutException ex) { throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.DOWNLOAD_TIMEOUT, "Runtime download timed out", ex); }
                catch (IOException ex) { throw new DirectCefRuntimeException(transportReason(ex,
                        DirectCefRuntimeFailureReason.DOWNLOAD_IO_ERROR), "Unable to connect to runtime download", ex); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw ex; }
                int status = response.statusCode();
                if (status >= 200 && status < 300) return new RuntimeDownloadSource.DownloadResponse(response.body(),
                        response.headers().firstValueAsLong("Content-Length").orElse(-1L), current);
                try { response.body().close(); } catch (IOException ignored) { }
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("Location").orElse("");
                    if (location.isBlank()) throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.DOWNLOAD_REDIRECT_REJECTED, "Runtime download redirect has no Location header");
                    current = resolveRedirect(current, location);
                    continue;
                }
                throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.DOWNLOAD_HTTP_ERROR,
                        "Runtime download returned HTTP " + status);
            }
            throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.DOWNLOAD_REDIRECT_REJECTED,
                    "Runtime download exceeded redirect limit");
        };
    }

    static URI validateHttpsUri(URI uri, DirectCefRuntimeFailureReason reason) {
        if (uri == null || !uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new DirectCefRuntimeException(reason, "Runtime download requires an HTTPS URI without userinfo");
        }
        return uri;
    }

    static URI resolveRedirect(URI current, String location) {
        if (current == null || location == null || location.isBlank()) {
            throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.DOWNLOAD_REDIRECT_REJECTED,
                    "Runtime download redirect has no Location URI");
        }
        try {
            return validateHttpsUri(current.resolve(location), DirectCefRuntimeFailureReason.DOWNLOAD_REDIRECT_REJECTED);
        } catch (IllegalArgumentException ex) {
            throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.DOWNLOAD_REDIRECT_REJECTED,
                    "Runtime download redirect URI is invalid", ex);
        }
    }

    static DirectCefRuntimeFailureReason transportReason(Throwable throwable,
                                                         DirectCefRuntimeFailureReason fallback) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof UnknownHostException) return DirectCefRuntimeFailureReason.DOWNLOAD_DNS_ERROR;
            if (current instanceof SSLException) return DirectCefRuntimeFailureReason.DOWNLOAD_TLS_ERROR;
            if (current instanceof ConnectException) return DirectCefRuntimeFailureReason.DOWNLOAD_CONNECT_ERROR;
            current = current.getCause();
        }
        return fallback;
    }
}
