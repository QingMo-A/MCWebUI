package dev.qingmo.mcwebui.nativecef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Catalog trust-anchor tests without a network or a real release asset. */
class DirectCefRuntimeReleaseCatalogTest {
    @TempDir Path temp;

    @Test void absentResourceMeansSourceUnconfigured() {
        Optional<DirectCefRuntimeReleaseDescriptor> result =
                DirectCefRuntimeReleaseCatalog.current(emptyLoader());
        assertTrue(result.isEmpty());
    }

    @Test void validResourceIsParsedAndIdentityValidated() {
        Optional<DirectCefRuntimeReleaseDescriptor> result = DirectCefRuntimeReleaseCatalog.current(
                resourceLoader(descriptorJson("https://downloads.example.invalid/runtime.zip")));
        assertTrue(result.isPresent());
        DirectCefRuntimeReleaseDescriptor descriptor = result.orElseThrow();
        assertEquals("cef-artifact", descriptor.artifactId());
        assertEquals("revision-1", descriptor.artifactRevision());
        assertEquals(1L, descriptor.packageSize());
        assertTrue(descriptor.sourceConfigured());
    }

    @Test void resourceWithoutUriRemainsExplicitlyUnconfigured() {
        DirectCefRuntimeReleaseDescriptor descriptor = DirectCefRuntimeReleaseCatalog.current(
                resourceLoader(descriptorJson(""))).orElseThrow();
        assertFalse(descriptor.sourceConfigured());
    }

    @Test void malformedResourceIsBuildReleaseConfigurationError() {
        DirectCefRuntimeException failure = assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeReleaseCatalog.current(resourceLoader("{not-json")));
        assertEquals(DirectCefRuntimeFailureReason.RELEASE_DESCRIPTOR_INVALID, failure.reason());
        assertTrue(failure.getMessage().contains("BUILD/RELEASE CONFIG ERROR"));
        assertTrue(failure.getMessage().contains("Bundled Direct CEF release descriptor is invalid"));
    }

    @Test void wrongRuntimeIsRejectedBeforeDownloadCanBeEnabled() {
        DirectCefRuntimeException failure = assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeReleaseCatalog.current(resourceLoader(
                        descriptorJson("https://downloads.example.invalid/runtime.zip")
                                .replace(DirectCefRuntimeRequirement.RUNTIME_ID, "cef-wrong"))));
        assertEquals(DirectCefRuntimeFailureReason.RELEASE_DESCRIPTOR_INVALID, failure.reason());
        assertTrue(failure.getMessage().contains("runtime identity"));
    }

    @Test void invalidHttpsSizeShaAndSchemaAreRejected() {
        String valid = descriptorJson("https://downloads.example.invalid/runtime.zip");
        assertCatalogFailure(valid.replace("\"packageSize\":1", "\"packageSize\":0"));
        assertCatalogFailure(valid.replace("\"packageSha256\":\"" + "0".repeat(64) + "\"",
                "\"packageSha256\":\"bad\""));
        assertCatalogFailure(valid.replace("\"descriptorVersion\":1", "\"descriptorVersion\":2"));
        assertCatalogFailure(valid.replace("https://downloads.example.invalid/runtime.zip",
                "http://downloads.example.invalid/runtime.zip"));
    }

    @Test void buildParserUsesSameStrictValidationAndRequiresConfiguredSource() throws Exception {
        Path descriptor = temp.resolve("descriptor.json");
        Files.writeString(descriptor, descriptorJson("https://downloads.example.invalid/runtime.zip"),
                StandardCharsets.UTF_8);
        assertEquals("cef-artifact", DirectCefRuntimeReleaseCatalog.parseAndValidate(descriptor).artifactId());

        Files.writeString(descriptor, descriptorJson(""), StandardCharsets.UTF_8);
        DirectCefRuntimeException failure = assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeReleaseCatalog.parseAndValidate(descriptor));
        assertEquals(DirectCefRuntimeFailureReason.RELEASE_DESCRIPTOR_INVALID, failure.reason());
    }

    private void assertCatalogFailure(String source) {
        DirectCefRuntimeException failure = assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeReleaseCatalog.current(resourceLoader(source)));
        assertEquals(DirectCefRuntimeFailureReason.RELEASE_DESCRIPTOR_INVALID, failure.reason());
        assertTrue(failure.getMessage().contains("BUILD/RELEASE CONFIG ERROR"));
    }

    private static ClassLoader emptyLoader() {
        return resourceLoader(null);
    }

    private static ClassLoader resourceLoader(String source) {
        byte[] bytes = source == null ? null : source.getBytes(StandardCharsets.UTF_8);
        return new ClassLoader(null) {
            @Override public java.io.InputStream getResourceAsStream(String name) {
                return DirectCefRuntimeReleaseCatalog.RESOURCE_PATH.equals(name) && bytes != null
                        ? new ByteArrayInputStream(bytes) : null;
            }
        };
    }

    private static String descriptorJson(String uri) {
        String uriField = uri.isEmpty() ? "" : ",\"downloadUri\":\"" + uri + "\"";
        return "{\"descriptorVersion\":1,\"artifactId\":\"cef-artifact\","
                + "\"artifactRevision\":\"revision-1\",\"packageFileName\":\"runtime.zip\","
                + "\"packageSize\":1,\"packageSha256\":\"" + "0".repeat(64) + "\","
                + "\"runtime\":{\"schemaVersion\":1,\"runtimeId\":\""
                + DirectCefRuntimeRequirement.RUNTIME_ID + "\",\"mcwebuiRuntimeAbi\":1,"
                + "\"cefVersion\":\"" + DirectCefRuntimeRequirement.CEF_VERSION + "\","
                + "\"chromiumVersion\":\"" + DirectCefRuntimeRequirement.CHROMIUM_VERSION + "\","
                + "\"platform\":\"windows\",\"arch\":\"x86_64\"}" + uriField + "}";
    }
}
