package dev.qingmo.mcwebui.nativecef;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DirectCefRuntimeDistributionTest {
    @TempDir Path temp;

    @AfterEach void clearNativeGuard() { DirectCefNativeLoader.resetForTests(); }

    @Test void validStandardRuntimeIsDiscoveredWithSeparateCache() throws Exception {
        Path instance = temp.resolve("instance");
        Path runtime = DirectCefRuntimeDiscovery.standardDirectory(instance);
        writeRuntime(runtime, Map.of(
                "mcwebui-direct-cef.dll", "jni",
                "mcwebui-cef-helper.exe", "helper",
                "libcef.dll", "cef",
                "chrome_elf.dll", "elf"));

        ValidatedDirectCefRuntime found = DirectCefRuntimeDiscovery.discover(instance, (Path) null);
        assertEquals(runtime.toAbsolutePath().normalize(), found.directory());
        assertEquals(ValidatedDirectCefRuntime.Source.STANDARD, found.source());
        assertEquals(DirectCefRuntimeRequirement.RUNTIME_ID, found.identity().runtimeId());
        assertEquals(instance.resolve("mcwebui/cache/cef/" + DirectCefRuntimeRequirement.RUNTIME_ID)
                .toAbsolutePath().normalize(), DirectCefRuntimeDiscovery.standardCacheDirectory(instance));
        assertNotEquals(found.directory(), DirectCefRuntimeDiscovery.standardCacheDirectory(instance));
    }

    @Test void explicitOverrideWinsAndInvalidOverrideDoesNotFallBack() throws Exception {
        Path instance = temp.resolve("instance");
        Path standard = DirectCefRuntimeDiscovery.standardDirectory(instance);
        writeRuntime(standard, Map.of("mcwebui-direct-cef.dll", "s-jni", "mcwebui-cef-helper.exe", "s-helper",
                "libcef.dll", "s-cef", "chrome_elf.dll", "s-elf"));
        Path override = temp.resolve("override");
        writeRuntime(override, Map.of("mcwebui-direct-cef.dll", "o-jni", "mcwebui-cef-helper.exe", "o-helper",
                "libcef.dll", "o-cef", "chrome_elf.dll", "o-elf"));
        assertEquals(ValidatedDirectCefRuntime.Source.OVERRIDE,
                DirectCefRuntimeDiscovery.discover(instance, override).source());

        Files.writeString(override.resolve("libcef.dll"), "corrupt", StandardCharsets.UTF_8);
        DirectCefRuntimeException failure = assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeDiscovery.discover(instance, override));
        assertEquals(DirectCefRuntimeFailureReason.FILE_SIZE_MISMATCH, failure.reason());
        assertTrue(failure.getMessage().contains("Automatic installation is not implemented yet"));
        assertTrue(failure.getMessage().contains(override.toAbsolutePath().normalize().toString()));
    }

    @Test void completeFileListRejectsUnexpectedFilesAndCaseDuplicates() throws Exception {
        Path runtime = temp.resolve("runtime");
        writeRuntime(runtime, Map.of("mcwebui-direct-cef.dll", "jni", "mcwebui-cef-helper.exe", "helper",
                "libcef.dll", "cef", "chrome_elf.dll", "elf"));
        Files.writeString(runtime.resolve("unexpected.pak"), "x", StandardCharsets.UTF_8);
        DirectCefRuntimeException extra = assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeValidator.validate(runtime));
        assertEquals(DirectCefRuntimeFailureReason.UNEXPECTED_FILE, extra.reason());

        Files.delete(runtime.resolve("unexpected.pak"));
        Files.writeString(runtime.resolve("LIBCEF.DLL"), "duplicate", StandardCharsets.UTF_8);
        DirectCefRuntimeException duplicate = assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeValidator.validate(runtime));
        // Windows resolves this spelling to libcef.dll and therefore reports the changed
        // payload; on a case-sensitive filesystem it is an unexpected extra file.
        assertTrue(duplicate.reason() == DirectCefRuntimeFailureReason.UNEXPECTED_FILE
                        || duplicate.reason() == DirectCefRuntimeFailureReason.FILE_SIZE_MISMATCH,
                duplicate.reason().toString());

        writeRuntime(runtime, Map.of("mcwebui-direct-cef.dll", "jni", "mcwebui-cef-helper.exe", "helper",
                "libcef.dll", "cef", "chrome_elf.dll", "elf"));
        String duplicateManifest = Files.readString(runtime.resolve("runtime.json"), StandardCharsets.UTF_8)
                .replace("{\"path\":\"chrome_elf.dll\"", "{\"path\":\"libcef.dll\"")
                .replace("\"size\":3,\"sha256\":\"" + sha256("elf"), "\"size\":3,\"sha256\":\"" + sha256("cef"));
        Files.writeString(runtime.resolve("runtime.json"), duplicateManifest, StandardCharsets.UTF_8);
        DirectCefRuntimeException duplicateManifestFailure = assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeValidator.validate(runtime));
        assertEquals(DirectCefRuntimeFailureReason.UNSAFE_PATH, duplicateManifestFailure.reason());
    }

    @Test void unsafePathsAndHashMismatchHaveTypedReasons() throws Exception {
        Path runtime = temp.resolve("runtime");
        writeRuntime(runtime, Map.of("mcwebui-direct-cef.dll", "jni", "mcwebui-cef-helper.exe", "helper",
                "libcef.dll", "cef", "chrome_elf.dll", "elf"));
        String json = Files.readString(runtime.resolve("runtime.json"), StandardCharsets.UTF_8)
                .replace("mcwebui-direct-cef.dll", "../escape.dll");
        Files.writeString(runtime.resolve("runtime.json"), json, StandardCharsets.UTF_8);
        DirectCefRuntimeException unsafe = assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeValidator.validate(runtime));
        assertEquals(DirectCefRuntimeFailureReason.UNSAFE_PATH, unsafe.reason());

        writeRuntime(runtime, Map.of("mcwebui-direct-cef.dll", "jni", "mcwebui-cef-helper.exe", "helper",
                "libcef.dll", "cef", "chrome_elf.dll", "elf"));
        String ads = Files.readString(runtime.resolve("runtime.json"), StandardCharsets.UTF_8)
                .replace("mcwebui-direct-cef.dll", "native.dll:stream");
        Files.writeString(runtime.resolve("runtime.json"), ads, StandardCharsets.UTF_8);
        DirectCefRuntimeException colon = assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeValidator.validate(runtime));
        assertEquals(DirectCefRuntimeFailureReason.UNSAFE_PATH, colon.reason());

        writeRuntime(runtime, Map.of("mcwebui-direct-cef.dll", "jni", "mcwebui-cef-helper.exe", "helper",
                "libcef.dll", "cef", "chrome_elf.dll", "elf"));
        String badHash = Files.readString(runtime.resolve("runtime.json"), StandardCharsets.UTF_8)
                .replaceFirst("[0-9a-f]{64}", "0".repeat(64));
        Files.writeString(runtime.resolve("runtime.json"), badHash, StandardCharsets.UTF_8);
        DirectCefRuntimeException hash = assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeValidator.validate(runtime));
        assertEquals(DirectCefRuntimeFailureReason.HASH_MISMATCH, hash.reason());
    }

    @Test void discoveryAndValidationReportSpecificSetupFailures() throws Exception {
        Path instance = temp.resolve("instance");
        Path runtime = DirectCefRuntimeDiscovery.standardDirectory(instance);
        writeRuntime(runtime, Map.of("mcwebui-direct-cef.dll", "jni", "mcwebui-cef-helper.exe", "helper",
                "libcef.dll", "cef", "chrome_elf.dll", "elf"));

        Files.delete(runtime.resolve("runtime.json"));
        assertEquals(DirectCefRuntimeFailureReason.MANIFEST_MISSING,
                assertThrows(DirectCefRuntimeException.class,
                        () -> DirectCefRuntimeValidator.validate(runtime)).reason());
        writeRuntime(runtime, Map.of("mcwebui-direct-cef.dll", "jni", "mcwebui-cef-helper.exe", "helper",
                "libcef.dll", "cef", "chrome_elf.dll", "elf"));

        Files.writeString(runtime.resolve("runtime.json"), "{not-json", StandardCharsets.UTF_8);
        assertEquals(DirectCefRuntimeFailureReason.MANIFEST_INVALID,
                assertThrows(DirectCefRuntimeException.class,
                        () -> DirectCefRuntimeValidator.validate(runtime)).reason());
        writeRuntime(runtime, Map.of("mcwebui-direct-cef.dll", "jni", "mcwebui-cef-helper.exe", "helper",
                "libcef.dll", "cef", "chrome_elf.dll", "elf"));

        Files.delete(runtime.resolve("libcef.dll"));
        assertEquals(DirectCefRuntimeFailureReason.MISSING_FILE,
                assertThrows(DirectCefRuntimeException.class,
                        () -> DirectCefRuntimeValidator.validate(runtime)).reason());
        writeRuntime(runtime, Map.of("mcwebui-direct-cef.dll", "jni", "mcwebui-cef-helper.exe", "helper",
                "libcef.dll", "cef", "chrome_elf.dll", "elf"));

        Files.writeString(runtime.resolve("libcef.dll"), "too-long", StandardCharsets.UTF_8);
        assertEquals(DirectCefRuntimeFailureReason.FILE_SIZE_MISMATCH,
                assertThrows(DirectCefRuntimeException.class,
                        () -> DirectCefRuntimeValidator.validate(runtime)).reason());
        writeRuntime(runtime, Map.of("mcwebui-direct-cef.dll", "jni", "mcwebui-cef-helper.exe", "helper",
                "libcef.dll", "cef", "chrome_elf.dll", "elf"));

        String absolute = Files.readString(runtime.resolve("runtime.json"), StandardCharsets.UTF_8)
                .replace("mcwebui-direct-cef.dll", "C:/escape.dll");
        Files.writeString(runtime.resolve("runtime.json"), absolute, StandardCharsets.UTF_8);
        assertEquals(DirectCefRuntimeFailureReason.UNSAFE_PATH,
                assertThrows(DirectCefRuntimeException.class,
                        () -> DirectCefRuntimeValidator.validate(runtime)).reason());

        assertEquals(DirectCefRuntimeFailureReason.NOT_FOUND,
                DirectCefRuntimeDiscovery.probe(temp.resolve("missing-instance"), null).failure().reason());

        writeRuntime(runtime, Map.of("mcwebui-direct-cef.dll", "jni", "mcwebui-cef-helper.exe", "helper",
                "libcef.dll", "cef", "chrome_elf.dll", "elf"));
        Path outside = temp.resolve("outside.dll");
        Files.writeString(outside, "outside", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(runtime.resolve("escape.dll"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ex) {
            // Windows CI without developer-mode/link privilege cannot create this fixture.
            return;
        }
        DirectCefRuntimeException link = assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeValidator.validate(runtime));
        assertEquals(DirectCefRuntimeFailureReason.SYMLINK_NOT_ALLOWED, link.reason());
    }

    @Test void identityMismatchesRemainIndependentlyMachineReadable() throws Exception {
        Path runtime = temp.resolve("runtime");
        writeRuntime(runtime, Map.of("mcwebui-direct-cef.dll", "jni", "mcwebui-cef-helper.exe", "helper",
                "libcef.dll", "cef", "chrome_elf.dll", "elf"));
        String base = Files.readString(runtime.resolve("runtime.json"), StandardCharsets.UTF_8);
        assertReason(base.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), runtime,
                DirectCefRuntimeFailureReason.UNSUPPORTED_SCHEMA);
        assertReason(base.replace(DirectCefRuntimeRequirement.RUNTIME_ID, "cef-other"), runtime,
                DirectCefRuntimeFailureReason.WRONG_RUNTIME_ID);
        assertReason(base.replace("\"cefVersion\":\"" + DirectCefRuntimeRequirement.CEF_VERSION + "\"",
                "\"cefVersion\":\"144.0.34\""), runtime,
                DirectCefRuntimeFailureReason.WRONG_CEF_VERSION);
        assertReason(base.replace("\"chromiumVersion\":\"" + DirectCefRuntimeRequirement.CHROMIUM_VERSION + "\"",
                "\"chromiumVersion\":\"144.0.7559.260\""), runtime,
                DirectCefRuntimeFailureReason.WRONG_CHROMIUM_VERSION);
        assertReason(base.replace("\"platform\":\"windows\"", "\"platform\":\"linux\""), runtime,
                DirectCefRuntimeFailureReason.WRONG_PLATFORM);
        assertReason(base.replace("\"arch\":\"x86_64\"", "\"arch\":\"aarch64\""), runtime,
                DirectCefRuntimeFailureReason.WRONG_ARCH);
        assertReason(base.replace("\"mcwebuiRuntimeAbi\":1", "\"mcwebuiRuntimeAbi\":2"), runtime,
                DirectCefRuntimeFailureReason.WRONG_ABI);
    }

    @Test void nativeLoaderPinsIdentityAndRootWithoutLoadingDlls() throws Exception {
        Path first = temp.resolve("first");
        writeRuntime(first, Map.of("mcwebui-direct-cef.dll", "jni", "mcwebui-cef-helper.exe", "helper",
                "libcef.dll", "cef", "chrome_elf.dll", "elf"));
        Path second = temp.resolve("second");
        writeRuntime(second, Map.of("mcwebui-direct-cef.dll", "jni", "mcwebui-cef-helper.exe", "helper",
                "libcef.dll", "cef", "chrome_elf.dll", "elf"));
        ValidatedDirectCefRuntime a = DirectCefRuntimeValidator.validate(first);
        ValidatedDirectCefRuntime b = DirectCefRuntimeValidator.validate(second);
        DirectCefNativeLoader.guard(a);
        DirectCefNativeLoader.guard(a);
        DirectCefRuntimeException conflict = assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefNativeLoader.guard(b));
        assertEquals(DirectCefRuntimeFailureReason.CONFLICTING_RUNTIME, conflict.reason());
        assertFalse(DirectCefNativeLoader.isLoaded());
    }

    private static void assertReason(String manifest, Path runtime, DirectCefRuntimeFailureReason reason) throws Exception {
        Files.writeString(runtime.resolve("runtime.json"), manifest, StandardCharsets.UTF_8);
        DirectCefRuntimeException failure = assertThrows(DirectCefRuntimeException.class,
                () -> DirectCefRuntimeValidator.validate(runtime));
        assertEquals(reason, failure.reason(), failure.getMessage());
    }

    private static void writeRuntime(Path runtime, Map<String, String> payload) throws Exception {
        Files.createDirectories(runtime);
        LinkedHashMap<String, String> values = new LinkedHashMap<>(payload);
        StringBuilder files = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            Path file = runtime.resolve(entry.getKey());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            if (!first) files.append(',');
            first = false;
            byte[] bytes = Files.readAllBytes(file);
            files.append("{\"path\":\"").append(entry.getKey()).append("\",\"size\":")
                    .append(bytes.length).append(",\"sha256\":\"")
                    .append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .append("\"}");
        }
        String nativeName = "mcwebui-direct-cef.dll";
        String helperName = "mcwebui-cef-helper.exe";
        String cefName = "libcef.dll";
        String elfName = "chrome_elf.dll";
        String json = "{\"schemaVersion\":1,\"mcwebuiRuntimeAbi\":1,\"runtimeId\":\""
                + DirectCefRuntimeRequirement.RUNTIME_ID + "\",\"cefVersion\":\""
                + DirectCefRuntimeRequirement.CEF_VERSION + "\",\"chromiumVersion\":\""
                + DirectCefRuntimeRequirement.CHROMIUM_VERSION + "\",\"platform\":\"windows\",\"arch\":\"x86_64\","
                + "\"entrypoints\":{\"native\":\"" + nativeName + "\",\"helper\":\"" + helperName
                + "\",\"cef\":\"" + cefName + "\",\"chromeElf\":\"" + elfName + "\"},\"files\":[" + files + "]}";
        Files.writeString(runtime.resolve("runtime.json"), json, StandardCharsets.UTF_8);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
