package dev.qingmo.mcwebui.nativecef;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Script-driven fresh-instance gate for LOCAL_FIXTURE and REAL_RELEASE modes. */
class DirectCefRuntimeFirstRunProofTest {
    @Test void downloadsImportsAndRediscoversPinnedRuntime() throws Exception {
        String mode = System.getProperty("mcwebui.proof.mode", "");
        assumeTrue(!mode.isBlank(), "script-driven proof is not configured");
        Path descriptorPath = requiredPath("mcwebui.proof.descriptor");
        Path instanceRoot = requiredPath("mcwebui.proof.instanceRoot");
        Path evidence = requiredPath("mcwebui.proof.evidence");
        DirectCefRuntimeReleaseDescriptor descriptor = DirectCefRuntimeReleaseDescriptor.parse(descriptorPath);
        DirectCefRuntimeDownloader downloader;

        if ("LOCAL_FIXTURE".equals(mode)) {
            Path packagePath = requiredPath("mcwebui.proof.package");
            assertFalse(descriptor.sourceConfigured(),
                    "LOCAL_FIXTURE must use the unconfigured candidate, never a fake production URL");
            descriptor = new DirectCefRuntimeReleaseDescriptor(descriptor.descriptorVersion(),
                    descriptor.artifactId(), descriptor.requirement(), descriptor.artifactRevision(),
                    descriptor.packageFileName(), descriptor.packageSize(), descriptor.runtimePayloadSize(),
                    descriptor.packageSha256(), URI.create("https://fixture.invalid/" + descriptor.packageFileName()));
            downloader = new DirectCefRuntimeDownloader((uri, cancellation) -> {
                InputStream body = Files.newInputStream(packagePath);
                return new RuntimeDownloadSource.DownloadResponse(body, Files.size(packagePath), uri);
            });
        } else if ("REAL_RELEASE".equals(mode)) {
            assertTrue(descriptor.sourceConfigured(), "REAL_RELEASE requires a configured HTTPS descriptor");
            downloader = new DirectCefRuntimeDownloader();
        } else {
            fail("mcwebui.proof.mode must be LOCAL_FIXTURE or REAL_RELEASE");
            return;
        }

        DirectCefRuntimeDiscovery.Probe before = DirectCefRuntimeDiscovery.probe(instanceRoot, (Path) null);
        assertFalse(before.valid(), "fresh-instance proof must start without an installed runtime");
        RuntimeDownloadResult result = downloader.download(descriptor, instanceRoot, null, null);
        assertTrue(result.success(), () -> String.valueOf(result.failure()));
        DirectCefRuntimeDiscovery.Probe after = DirectCefRuntimeDiscovery.probe(instanceRoot, (Path) null);
        assertTrue(after.valid(), () -> String.valueOf(after.failure()));
        assertEquals(ValidatedDirectCefRuntime.Source.STANDARD, after.runtime().source());

        Files.createDirectories(evidence.toAbsolutePath().normalize().getParent());
        String json = "{\"schemaVersion\":1,\"status\":\"PASS\",\"mode\":\"" + mode
                + "\",\"runtimeId\":\"" + after.runtime().identity().runtimeId()
                + "\",\"artifactRevision\":\"" + descriptor.artifactRevision()
                + "\",\"runtimePayloadSize\":" + descriptor.runtimePayloadSize() + "}\n";
        Files.writeString(evidence, json, StandardCharsets.UTF_8);
    }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property, "");
        assertFalse(value.isBlank(), property + " is required");
        return Path.of(value).toAbsolutePath().normalize();
    }
}
