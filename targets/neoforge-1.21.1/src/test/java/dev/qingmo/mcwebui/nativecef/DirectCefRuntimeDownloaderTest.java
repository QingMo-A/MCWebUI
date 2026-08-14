package dev.qingmo.mcwebui.nativecef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.ConnectException;
import javax.net.ssl.SSLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic downloader/importer tests; no network or release asset is used. */
class DirectCefRuntimeDownloaderTest {
    @TempDir Path temp;

    @Test void descriptorPinsIdentityAndArtifactFields() {
        DirectCefRuntimeRequirement requirement = DirectCefRuntimeRequirement.required();
        DirectCefRuntimeReleaseDescriptor descriptor = new DirectCefRuntimeReleaseDescriptor(1, "cef-artifact",
                requirement, "r1", "runtime.zip", 1,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                URI.create("https://downloads.example.invalid/runtime.zip"));
        assertTrue(descriptor.sourceConfigured());
        descriptor.validateFor(requirement);
        String json = "{\"descriptorVersion\":1,\"artifactId\":\"cef-artifact\","
                + "\"artifactRevision\":\"r1\",\"packageFileName\":\"runtime.zip\",\"packageSize\":1,"
                + "\"packageSha256\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\","
                + "\"runtime\":{\"schemaVersion\":1,\"runtimeId\":\"cef-144.0.33-cb4715c\","
                + "\"mcwebuiRuntimeAbi\":1,\"cefVersion\":\"144.0.33\","
                + "\"chromiumVersion\":\"144.0.7559.259\",\"platform\":\"windows\",\"arch\":\"x86_64\"},"
                + "\"downloadUri\":\"https://downloads.example.invalid/runtime.zip\"}";
        assertEquals(descriptor, DirectCefRuntimeReleaseDescriptor.parse(json));

        DirectCefRuntimeReleaseDescriptor sized = DirectCefRuntimeReleaseDescriptor.parse(
                json.replace("\"packageSize\":1", "\"packageSize\":1,\"runtimePayloadSize\":123"));
        assertEquals(123L, sized.runtimePayloadSize());
    }

    @Test void descriptorRejectsNonHttpsAndInvalidSize() {
        DirectCefRuntimeRequirement req = DirectCefRuntimeRequirement.required();
        assertThrows(DirectCefRuntimeException.class, () -> new DirectCefRuntimeReleaseDescriptor(1, "../escape", req,
                "r1", "x.zip", 1, "0".repeat(64), URI.create("https://example.invalid/x.zip")));
        assertThrows(DirectCefRuntimeException.class, () -> new DirectCefRuntimeReleaseDescriptor(1, "a", req,
                "r1", "x.zip", 0, "0".repeat(64), URI.create("https://example.invalid/x.zip")));
        assertThrows(DirectCefRuntimeException.class, () -> new DirectCefRuntimeReleaseDescriptor(1, "a", req,
                "r1", "x.zip", 1, "0".repeat(64), URI.create("http://example.invalid/x.zip")));
        assertThrows(DirectCefRuntimeException.class, () -> DirectCefRuntimeReleaseDescriptor.parse(
                "{\"descriptorVersion\":1,\"artifactId\":\"a\",\"artifactRevision\":\"r1\","
                        + "\"packageFileName\":\"x.zip\",\"packageSize\":1,\"packageSha256\":\""
                        + "0".repeat(64) + "\",\"runtime\":{},\"downloadUri\":\"file:///x\"}"));
        assertThrows(DirectCefRuntimeException.class, () -> DirectCefRuntimeReleaseDescriptor.parse("{}"));
    }

    @Test void fakeDownloadVerifiesHashCallsImporterAndCleansPart() throws Exception {
        byte[] packageBytes = runtimePackage();
        DirectCefRuntimeReleaseDescriptor descriptor = descriptor(packageBytes, sha256(packageBytes));
        DirectCefRuntimeDownloader downloader = new DirectCefRuntimeDownloader((uri, token) ->
                new RuntimeDownloadSource.DownloadResponse(new ByteArrayInputStream(packageBytes), packageBytes.length));
        RuntimeDownloadResult result = downloader.download(descriptor, temp.resolve("instance"), null, null);
        assertTrue(result.success(), String.valueOf(result.failure()));
        assertNotNull(result.importResult());
        assertTrue(DirectCefRuntimeDiscovery.probe(temp.resolve("instance"), (Path) null).valid());
        Path downloads = temp.resolve("instance/mcwebui/runtime/.downloads");
        if (Files.exists(downloads)) try (var files = Files.list(downloads)) { assertEquals(0, files.count()); }
    }

    @Test void shortBodyFailsExactSizeAndCleansPart() throws Exception {
        byte[] packageBytes = runtimePackage();
        byte[] shortBody = java.util.Arrays.copyOf(packageBytes, packageBytes.length - 1);
        DirectCefRuntimeReleaseDescriptor descriptor = descriptor(packageBytes, sha256(packageBytes));
        RuntimeDownloadResult result = new DirectCefRuntimeDownloader((uri, token) ->
                new RuntimeDownloadSource.DownloadResponse(new ByteArrayInputStream(shortBody), -1))
                .download(descriptor, temp.resolve("short"), null, null);
        assertEquals(RuntimeDownloadResult.Status.FAILED, result.status());
        assertEquals(DirectCefRuntimeFailureReason.DOWNLOAD_SIZE_MISMATCH, result.failure().reason());
        assertNoPart(temp.resolve("short"));
    }

    @Test void oversizedBodyFailsBeforeImporter() throws Exception {
        byte[] packageBytes = runtimePackage();
        byte[] large = java.util.Arrays.copyOf(packageBytes, packageBytes.length + 1);
        DirectCefRuntimeReleaseDescriptor descriptor = descriptor(packageBytes, sha256(packageBytes));
        RuntimeDownloadResult result = new DirectCefRuntimeDownloader((uri, token) ->
                new RuntimeDownloadSource.DownloadResponse(new ByteArrayInputStream(large), -1))
                .download(descriptor, temp.resolve("large"), null, null);
        assertEquals(RuntimeDownloadResult.Status.FAILED, result.status());
        assertEquals(DirectCefRuntimeFailureReason.DOWNLOAD_SIZE_MISMATCH, result.failure().reason());
        assertNoPart(temp.resolve("large"));
    }

    @Test void hashMismatchAndCancellationAreTyped() throws Exception {
        byte[] packageBytes = runtimePackage();
        DirectCefRuntimeReleaseDescriptor badHash = descriptor(packageBytes, "0".repeat(64));
        RuntimeDownloadResult mismatch = new DirectCefRuntimeDownloader((uri, token) ->
                new RuntimeDownloadSource.DownloadResponse(new ByteArrayInputStream(packageBytes), packageBytes.length))
                .download(badHash, temp.resolve("hash"), null, null);
        assertEquals(DirectCefRuntimeFailureReason.DOWNLOAD_HASH_MISMATCH, mismatch.failure().reason());
        AtomicBoolean cancelled = new AtomicBoolean(true);
        RuntimeDownloadResult cancelledResult = new DirectCefRuntimeDownloader((uri, token) ->
                new RuntimeDownloadSource.DownloadResponse(new ByteArrayInputStream(packageBytes), packageBytes.length))
                .download(descriptor(packageBytes, sha256(packageBytes)), temp.resolve("cancel"), null,
                        cancelled::get);
        assertEquals(RuntimeDownloadResult.Status.CANCELLED, cancelledResult.status());
        assertEquals(DirectCefRuntimeFailureReason.DOWNLOAD_CANCELLED, cancelledResult.failure().reason());
    }

    @Test void transportFailuresAndLiveProgressRemainTyped() throws Exception {
        byte[] packageBytes = runtimePackage();
        DirectCefRuntimeReleaseDescriptor descriptor = descriptor(packageBytes, sha256(packageBytes));
        RuntimeDownloadResult timeout = new DirectCefRuntimeDownloader((uri, token) -> {
            throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.DOWNLOAD_TIMEOUT, "timeout");
        }).download(descriptor, temp.resolve("timeout"), null, null);
        assertEquals(DirectCefRuntimeFailureReason.DOWNLOAD_TIMEOUT, timeout.failure().reason());
        RuntimeDownloadResult http = new DirectCefRuntimeDownloader((uri, token) -> {
            throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.DOWNLOAD_HTTP_ERROR, "HTTP 503");
        }).download(descriptor, temp.resolve("http"), null, null);
        assertEquals(DirectCefRuntimeFailureReason.DOWNLOAD_HTTP_ERROR, http.failure().reason());

        DirectCefRuntimeReleaseDescriptor unavailable = new DirectCefRuntimeReleaseDescriptor(1, "cef-test",
                DirectCefRuntimeRequirement.required(), "r1", "runtime.zip", packageBytes.length,
                sha256(packageBytes), null);
        RuntimeDownloadResult missing = new DirectCefRuntimeDownloader((uri, token) ->
                new RuntimeDownloadSource.DownloadResponse(new ByteArrayInputStream(packageBytes), packageBytes.length))
                .download(unavailable, temp.resolve("missing"), null, null);
        assertEquals(DirectCefRuntimeFailureReason.RELEASE_DESCRIPTOR_INVALID, missing.failure().reason());

        List<RuntimeDownloadProgress.Phase> phases = new ArrayList<>();
        RuntimeDownloadResult success = new DirectCefRuntimeDownloader((uri, token) ->
                new RuntimeDownloadSource.DownloadResponse(new ByteArrayInputStream(packageBytes), packageBytes.length))
                .download(descriptor, temp.resolve("progress"), p -> phases.add(p.phase()), null);
        assertTrue(success.success());
        assertTrue(phases.contains(RuntimeDownloadProgress.Phase.DOWNLOADING));
        assertTrue(phases.contains(RuntimeDownloadProgress.Phase.INSTALLING));
        assertEquals(RuntimeDownloadProgress.Phase.COMPLETE, phases.get(phases.size() - 1));
        assertEquals(DirectCefRuntimeFailureReason.DOWNLOAD_DNS_ERROR,
                DirectCefRuntimeDownloader.transportReason(new UnknownHostException("dns"), DirectCefRuntimeFailureReason.DOWNLOAD_IO_ERROR));
        assertEquals(DirectCefRuntimeFailureReason.DOWNLOAD_CONNECT_ERROR,
                DirectCefRuntimeDownloader.transportReason(new ConnectException("connect"), DirectCefRuntimeFailureReason.DOWNLOAD_IO_ERROR));
        assertEquals(DirectCefRuntimeFailureReason.DOWNLOAD_TLS_ERROR,
                DirectCefRuntimeDownloader.transportReason(new SSLException("tls"), DirectCefRuntimeFailureReason.DOWNLOAD_IO_ERROR));
    }

    @Test void redirectPolicyAllowsHttpsAndRejectsDowngradeOrOtherSchemes() {
        URI base = URI.create("https://downloads.example.invalid/releases/runtime.zip");
        assertEquals(URI.create("https://downloads.example.invalid/releases/v2.zip"),
                DirectCefRuntimeDownloader.resolveRedirect(base, "v2.zip"));
        assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeDownloader.resolveRedirect(base, "http://downloads.example.invalid/runtime.zip"));
        assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeDownloader.resolveRedirect(base, "file:///runtime.zip"));
        assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeDownloader.resolveRedirect(base, "https://downloads.example.invalid/runtime.zip#fragment"));
    }

    @Test void diskStagingFailuresAreTypedBeforeTransport() throws Exception {
        byte[] packageBytes = runtimePackage();
        Path blockedRoot = temp.resolve("blocked-root");
        Files.writeString(blockedRoot, "not a directory");
        DirectCefRuntimeReleaseDescriptor descriptor = descriptor(packageBytes, sha256(packageBytes));
        RuntimeDownloadResult result = new DirectCefRuntimeDownloader((uri, token) ->
                new RuntimeDownloadSource.DownloadResponse(new ByteArrayInputStream(packageBytes), packageBytes.length))
                .download(descriptor, blockedRoot, null, null);
        assertEquals(DirectCefRuntimeFailureReason.DOWNLOAD_WRITE_ERROR, result.failure().reason());

        DirectCefRuntimeReleaseDescriptor enormous = new DirectCefRuntimeReleaseDescriptor(1, "cef-test",
                DirectCefRuntimeRequirement.required(), "r1", "runtime.zip", Long.MAX_VALUE,
                "0".repeat(64), URI.create("https://downloads.example.invalid/runtime.zip"));
        RuntimeDownloadResult disk = new DirectCefRuntimeDownloader((uri, token) -> {
            throw new AssertionError("transport must not start when disk bound fails");
        }).download(enormous, temp.resolve("disk"), null, null);
        assertEquals(DirectCefRuntimeFailureReason.INSUFFICIENT_DISK_SPACE, disk.failure().reason());
    }

    @Test void freshInstallDiskBudgetIncludesZipPayloadAndMargin() {
        assertEquals(10L + 30L + DirectCefRuntimeDownloader.STAGING_MARGIN_BYTES,
                DirectCefRuntimeDownloader.requiredFreshInstallBytes(10L, 30L));
        assertEquals(20L + DirectCefRuntimeDownloader.STAGING_MARGIN_BYTES,
                DirectCefRuntimeDownloader.requiredFreshInstallBytes(10L, 0L));
        assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeDownloader.requiredFreshInstallBytes(Long.MAX_VALUE, 1L));
    }

    @Test void cancellationClosesBlockingResponseAndStopsWorker() throws Exception {
        byte[] packageBytes = runtimePackage();
        DirectCefRuntimeReleaseDescriptor descriptor = descriptor(packageBytes, sha256(packageBytes));
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch streamClosed = new CountDownLatch(1);
        InputStream blocking = new InputStream() {
            @Override public int read() throws IOException {
                readStarted.countDown();
                try {
                    if (!streamClosed.await(5, TimeUnit.SECONDS)) throw new IOException("test read timeout");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("test read interrupted", ex);
                }
                throw new IOException("stream closed by cancellation");
            }
            @Override public void close() { streamClosed.countDown(); }
        };
        RuntimeSetupJobLifecycle lifecycle = new RuntimeSetupJobLifecycle();
        AtomicReference<RuntimeDownloadResult> result = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        assertTrue(lifecycle.begin());
        lifecycle.execute(() -> {
            try {
                result.set(new DirectCefRuntimeDownloader((uri, token) ->
                        new RuntimeDownloadSource.DownloadResponse(blocking, -1))
                        .download(descriptor, temp.resolve("blocking-cancel"), null, lifecycle));
            } finally {
                lifecycle.finish();
                finished.countDown();
            }
        });
        assertTrue(readStarted.await(5, TimeUnit.SECONDS));
        lifecycle.cancel();
        assertTrue(streamClosed.await(1, TimeUnit.SECONDS));
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals(RuntimeDownloadResult.Status.CANCELLED, result.get().status());
        assertEquals(DirectCefRuntimeFailureReason.DOWNLOAD_CANCELLED, result.get().failure().reason());
        assertNoPart(temp.resolve("blocking-cancel"));
        lifecycle.dispose();
        assertTrue(lifecycle.isExecutorShutdown());
    }

    private byte[] runtimePackage() {
        return TestRuntimePackages.packageZip(TestRuntimePackages.runtimeFiles(
                Map.of("locales/en-US.pak", TestRuntimePackages.utf8("pak"))));
    }

    private DirectCefRuntimeReleaseDescriptor descriptor(byte[] bytes, String sha) {
        return new DirectCefRuntimeReleaseDescriptor(1, "cef-test", DirectCefRuntimeRequirement.required(),
                "r1", "runtime.zip", bytes.length, sha,
                URI.create("https://downloads.example.invalid/runtime.zip"));
    }

    private static String sha256(byte[] bytes) { return TestRuntimePackages.sha256(bytes); }

    private static void assertNoPart(Path instance) throws Exception {
        Path downloads = instance.resolve("mcwebui/runtime/.downloads");
        if (Files.exists(downloads)) try (var files = Files.list(downloads)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }
}
