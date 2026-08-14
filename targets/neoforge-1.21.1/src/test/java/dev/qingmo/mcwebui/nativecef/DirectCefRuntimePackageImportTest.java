package dev.qingmo.mcwebui.nativecef;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Phase B security/corruption/concurrency matrix for the offline package importer. */
class DirectCefRuntimePackageImportTest {
    @TempDir Path temp;

    @AfterEach void resetState() {
        DirectCefNativeLoader.resetForTests();
        DirectCefRuntimePackageImporter.publishInterceptor = null;
    }

    @Test void validPackageInstallsAndRediscoveryPasses() throws Exception {
        Path instance = instanceRoot();
        Path packageFile = writePackage(defaultFiles());
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instance);
        assertEquals(RuntimeImportResult.Status.INSTALLED, result.status(), failureText(result));
        assertNull(result.failure());
        Path standard = DirectCefRuntimeDiscovery.standardDirectory(instance);
        assertEquals(standard, result.finalDirectory());
        assertTrue(Files.isRegularFile(standard.resolve("runtime.json")));
        assertTrue(Files.isRegularFile(standard.resolve("locales/en-US.pak")));
        assertTrue(Files.isRegularFile(standard.resolve("libcef.dll")));
        DirectCefRuntimeDiscovery.Probe probe = DirectCefRuntimeDiscovery.probe(instance, (Path) null);
        assertTrue(probe.valid(), "standard rediscovery failed: " + probe.failure());
        assertEquals(ValidatedDirectCefRuntime.Source.STANDARD, probe.runtime().source());
        assertTrue(Files.isDirectory(instance.resolve("mcwebui/runtime/.locks")));
        assertEquals(List.of(), leftoverSiblings(standard));
    }

    @Test void secondImportReturnsAlreadyInstalledWithoutTouchingRuntime() throws Exception {
        Path instance = instanceRoot();
        Path packageFile = writePackage(defaultFiles());
        DirectCefRuntimePackageImporter.importPackage(packageFile, instance);
        Path standard = DirectCefRuntimeDiscovery.standardDirectory(instance);
        byte[] before = Files.readAllBytes(standard.resolve("libcef.dll"));
        RuntimeImportResult second = DirectCefRuntimePackageImporter.importPackage(packageFile, instance);
        assertEquals(RuntimeImportResult.Status.ALREADY_INSTALLED, second.status());
        assertTrue(second.runtime() != null && second.runtime().source() == ValidatedDirectCefRuntime.Source.STANDARD);
        assertArrayEquals(before, Files.readAllBytes(standard.resolve("libcef.dll")));
        assertEquals(List.of(), leftoverSiblings(standard));
    }

    @Test void missingRuntimeJsonIsRejectedBeforeAnyExtraction() throws Exception {
        Path instance = instanceRoot();
        Map<String, byte[]> files = defaultFiles();
        List<TestRuntimePackages.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            entries.add(new TestRuntimePackages.Entry(file.getKey(), file.getValue()));
        }
        Path packageFile = temp.resolve("no-manifest.zip");
        Files.write(packageFile, TestRuntimePackages.zip(entries));
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instance);
        assertFailedReason(result, DirectCefRuntimeFailureReason.PACKAGE_INVALID);
        assertTrue(result.failure().getMessage().contains("runtime.json"));
        assertNoInstallation(instance);
    }

    @Test void duplicateRuntimeJsonIsRejected() throws Exception {
        Path instance = instanceRoot();
        Map<String, byte[]> files = defaultFiles();
        List<TestRuntimePackages.Entry> entries = TestRuntimePackages.entries(files,
                TestRuntimePackages.manifestJson(files));
        entries.add(new TestRuntimePackages.Entry("RUNTIME.JSON", TestRuntimePackages.utf8("{}")));
        Path packageFile = temp.resolve("dup-manifest.zip");
        Files.write(packageFile, TestRuntimePackages.zip(entries));
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instance);
        assertFailedReason(result, DirectCefRuntimeFailureReason.PACKAGE_INVALID);
        assertTrue(result.failure().getMessage().toLowerCase(Locale.ROOT).contains("duplicate"),
                result.failure().getMessage());
    }

    @Test void identityMismatchesRemainTyped() throws Exception {
        String base = TestRuntimePackages.manifestJson(defaultFiles());
        assertPackageReason(base.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                DirectCefRuntimeFailureReason.UNSUPPORTED_SCHEMA);
        assertPackageReason(base.replace(DirectCefRuntimeRequirement.RUNTIME_ID, "cef-other"),
                DirectCefRuntimeFailureReason.WRONG_RUNTIME_ID);
        assertPackageReason(base.replace("\"mcwebuiRuntimeAbi\":1", "\"mcwebuiRuntimeAbi\":2"),
                DirectCefRuntimeFailureReason.WRONG_ABI);
        assertPackageReason(base.replace("\"platform\":\"windows\"", "\"platform\":\"linux\""),
                DirectCefRuntimeFailureReason.WRONG_PLATFORM);
        assertPackageReason(base.replace("\"arch\":\"x86_64\"", "\"arch\":\"aarch64\""),
                DirectCefRuntimeFailureReason.WRONG_ARCH);
        // A manifest with no files passes the package entry check only when the ZIP
        // contains nothing but runtime.json; the shared Phase A manifest gate rejects it.
        String empty = base.replaceAll("\"files\":\\[.*\\]", "\"files\":[]");
        Path emptyPackage = temp.resolve("empty-manifest.zip");
        Files.write(emptyPackage, TestRuntimePackages.zip(
                List.of(new TestRuntimePackages.Entry("runtime.json", TestRuntimePackages.utf8(empty)))));
        RuntimeImportResult emptyResult = DirectCefRuntimePackageImporter.importPackage(emptyPackage,
                temp.resolve("instance-empty"));
        assertFailedReason(emptyResult, DirectCefRuntimeFailureReason.MANIFEST_INVALID);
    }

    @Test void missingManifestFileEntryIsRejected() throws Exception {
        Map<String, byte[]> files = defaultFiles();
        Map<String, byte[]> smaller = Map.of("mcwebui-direct-cef.dll", TestRuntimePackages.utf8("jni"),
                "mcwebui-cef-helper.exe", TestRuntimePackages.utf8("helper"),
                "libcef.dll", TestRuntimePackages.utf8("cef"));
        String manifest = TestRuntimePackages.manifestJson(files); // still lists chrome_elf.dll
        Path packageFile = temp.resolve("missing-entry.zip");
        Files.write(packageFile, TestRuntimePackages.packageZip(smaller, manifest));
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instanceRoot());
        assertFailedReason(result, DirectCefRuntimeFailureReason.PACKAGE_INVALID);
        assertTrue(result.failure().getMessage().contains("missing"), result.failure().getMessage());
        assertTrue(result.failure().getMessage().contains("chrome_elf.dll"), result.failure().getMessage());
    }

    @Test void extraEntryIsRejected() throws Exception {
        Map<String, byte[]> files = defaultFiles();
        files.put("extra.dll", TestRuntimePackages.utf8("surprise"));
        Path packageFile = temp.resolve("extra.zip");
        Files.write(packageFile, TestRuntimePackages.packageZip(files,
                TestRuntimePackages.manifestJson(defaultFiles())));
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instanceRoot());
        assertFailedReason(result, DirectCefRuntimeFailureReason.PACKAGE_INVALID);
        assertTrue(result.failure().getMessage().contains("extra.dll"));
    }

    @Test void unnecessaryDirectoryEntryIsRejected() throws Exception {
        Map<String, byte[]> files = defaultFiles();
        List<TestRuntimePackages.Entry> entries = TestRuntimePackages.entries(files,
                TestRuntimePackages.manifestJson(files));
        entries.add(0, new TestRuntimePackages.Entry("empty/", new byte[0], 0, 0));
        Path packageFile = temp.resolve("empty-dir.zip");
        Files.write(packageFile, TestRuntimePackages.zip(entries));
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instanceRoot());
        assertFailedReason(result, DirectCefRuntimeFailureReason.PACKAGE_INVALID);
        assertTrue(result.failure().getMessage().contains("empty/"));
    }

    @Test void unsafeEntryPathsAreRejected() throws Exception {
        Map<String, byte[]> files = defaultFiles();
        String manifest = TestRuntimePackages.manifestJson(files);
        assertEntryReason(files, manifest, "../evil.dll", DirectCefRuntimeFailureReason.UNSAFE_PATH);
        assertEntryReason(files, manifest, "/evil.dll", DirectCefRuntimeFailureReason.UNSAFE_PATH);
        assertEntryReason(files, manifest, "sub\\evil.dll", DirectCefRuntimeFailureReason.UNSAFE_PATH);
        assertEntryReason(files, manifest, "C:/evil.dll", DirectCefRuntimeFailureReason.UNSAFE_PATH);
        assertEntryReason(files, manifest, "locales/../evil.dll", DirectCefRuntimeFailureReason.UNSAFE_PATH);
        assertEntryReason(files, manifest, "./evil.dll", DirectCefRuntimeFailureReason.UNSAFE_PATH);
        assertEntryReason(files, manifest, "a//b.dll", DirectCefRuntimeFailureReason.UNSAFE_PATH);
    }

    @Test void caseInsensitiveDuplicateEntriesAreRejected() throws Exception {
        Map<String, byte[]> files = defaultFiles();
        List<TestRuntimePackages.Entry> entries = TestRuntimePackages.entries(files,
                TestRuntimePackages.manifestJson(files));
        entries.add(new TestRuntimePackages.Entry("LIBCEF.DLL", TestRuntimePackages.utf8("cef")));
        Path packageFile = temp.resolve("case-dup.zip");
        Files.write(packageFile, TestRuntimePackages.zip(entries));
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instanceRoot());
        assertFailedReason(result, DirectCefRuntimeFailureReason.PACKAGE_INVALID);
        assertTrue(result.failure().getMessage().toLowerCase().contains("duplicate"));
    }

    @Test void declaredSizeMismatchIsRejected() throws Exception {
        Map<String, byte[]> files = defaultFiles();
        String manifest = TestRuntimePackages.manifestJson(files);
        List<TestRuntimePackages.Entry> entries = TestRuntimePackages.entries(files, manifest);
        entries.replaceAll(e -> e.name().equals("libcef.dll") ? e.withDeclaredSize(99) : e);
        Path packageFile = temp.resolve("wrong-size.zip");
        Files.write(packageFile, TestRuntimePackages.zip(entries));
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instanceRoot());
        assertFailedReason(result, DirectCefRuntimeFailureReason.FILE_SIZE_MISMATCH);
    }

    @Test void truncatedEntryIsRejected() throws Exception {
        Map<String, byte[]> files = defaultFiles();
        String manifest = TestRuntimePackages.manifestJson(files);
        byte[] cef = files.get("libcef.dll");
        List<TestRuntimePackages.Entry> entries = TestRuntimePackages.entries(files, manifest);
        entries.replaceAll(e -> e.name().equals("libcef.dll")
                ? new TestRuntimePackages.Entry("libcef.dll", java.util.Arrays.copyOf(cef, cef.length - 1),
                        cef.length, cef.length - 1)
                : e);
        Path packageFile = temp.resolve("truncated.zip");
        Files.write(packageFile, TestRuntimePackages.zip(entries));
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instanceRoot());
        // The entry either reads short (FILE_SIZE_MISMATCH) or spills into following
        // bytes and fails the authoritative SHA-256 (HASH_MISMATCH); both are typed
        // failures and neither can produce a runtime.
        assertTrue(result.status() == RuntimeImportResult.Status.FAILED
                        && (result.failure().reason() == DirectCefRuntimeFailureReason.FILE_SIZE_MISMATCH
                        || result.failure().reason() == DirectCefRuntimeFailureReason.HASH_MISMATCH),
                failureText(result));
        assertNoInstallation(instanceRoot());
    }

    @Test void truncatedPackageFileIsRejected() throws Exception {
        Map<String, byte[]> files = defaultFiles();
        byte[] zip = TestRuntimePackages.packageZip(files);
        Path packageFile = temp.resolve("truncated-file.zip");
        Files.write(packageFile, java.util.Arrays.copyOf(zip, zip.length - 12)); // cut into the CEN region
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instanceRoot());
        assertFailedReason(result, DirectCefRuntimeFailureReason.PACKAGE_INVALID);
        assertNoInstallation(instanceRoot());
    }

    @Test void storedEntryCrcIsNotTrustedButContentShaIsAuthoritative() throws Exception {
        // java.util.zip only CRC-checks deflated entries; stored entries with a bad
        // CRC field but correct content still install, which is safe because the
        // runtime manifest SHA-256 is the actual trust boundary, never the ZIP CRC.
        Map<String, byte[]> files = defaultFiles();
        String manifest = TestRuntimePackages.manifestJson(files);
        byte[] cef = files.get("libcef.dll");
        List<TestRuntimePackages.Entry> entries = TestRuntimePackages.entries(files, manifest);
        entries.replaceAll(e -> e.name().equals("libcef.dll")
                ? new TestRuntimePackages.Entry("libcef.dll", cef, cef.length, cef.length - 1)
                : e);
        Path packageFile = temp.resolve("bad-crc.zip");
        Files.write(packageFile, TestRuntimePackages.zip(entries));
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instanceRoot());
        assertEquals(RuntimeImportResult.Status.INSTALLED, result.status(), failureText(result));
    }

    @Test void contentHashMismatchIsRejected() throws Exception {
        Map<String, byte[]> files = defaultFiles();
        String manifest = TestRuntimePackages.manifestJson(files)
                .replaceFirst("[0-9a-f]{64}", "0".repeat(64));
        Path packageFile = temp.resolve("bad-content.zip");
        Files.write(packageFile, TestRuntimePackages.packageZip(files, manifest));
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instanceRoot());
        assertFailedReason(result, DirectCefRuntimeFailureReason.HASH_MISMATCH);
        assertNoInstallation(instanceRoot());
    }

    @Test void garbageTailAfterDeclaredEntryIsNeverExtracted() throws Exception {
        // A hostile ZIP can stuff extra bytes behind an entry's declared data. The
        // importer only ever reads the declared/expected bytes, so the tail is inert.
        Map<String, byte[]> files = defaultFiles();
        String manifest = TestRuntimePackages.manifestJson(files);
        byte[] cef = files.get("libcef.dll");
        byte[] padded = java.util.Arrays.copyOf(cef, cef.length + 8);
        List<TestRuntimePackages.Entry> entries = TestRuntimePackages.entries(files, manifest);
        entries.replaceAll(e -> e.name().equals("libcef.dll")
                ? new TestRuntimePackages.Entry("libcef.dll", padded, cef.length, cef.length)
                : e);
        Path packageFile = temp.resolve("tail.zip");
        Files.write(packageFile, TestRuntimePackages.zip(entries));
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instanceRoot());
        assertEquals(RuntimeImportResult.Status.INSTALLED, result.status(), failureText(result));
        assertArrayEquals(cef, Files.readAllBytes(DirectCefRuntimeDiscovery.standardDirectory(instanceRoot())
                .resolve("libcef.dll")));
    }

    @Test void hugeDeclaredSizesAreRejectedAsZipBombs() throws Exception {
        Map<String, byte[]> files = defaultFiles();
        String manifest = TestRuntimePackages.manifestJson(files)
                .replace("\"path\":\"libcef.dll\",\"size\":3", "\"path\":\"libcef.dll\",\"size\":2147483648");
        List<TestRuntimePackages.Entry> entries = TestRuntimePackages.entries(files, manifest);
        entries.replaceAll(e -> e.name().equals("libcef.dll") ? e.withDeclaredSize(1L << 31) : e);
        Path packageFile = temp.resolve("huge.zip");
        Files.write(packageFile, TestRuntimePackages.zip(entries));
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instanceRoot());
        assertFailedReason(result, DirectCefRuntimeFailureReason.PACKAGE_INVALID);
    }

    @Test void totalUncompressedBoundIsEnforced() throws Exception {
        Map<String, byte[]> files = defaultFiles();
        String manifest = TestRuntimePackages.manifestJson(files);
        long oneGiB = 1L << 30;
        for (String name : new String[]{"mcwebui-direct-cef.dll", "mcwebui-cef-helper.exe", "libcef.dll",
                "chrome_elf.dll", "locales/en-US.pak"}) {
            manifest = manifest.replace("\"path\":\"" + name + "\",\"size\":"
                    + files.get(name).length, "\"path\":\"" + name + "\",\"size\":" + oneGiB);
        }
        List<TestRuntimePackages.Entry> entries = TestRuntimePackages.entries(files, manifest);
        entries.replaceAll(e -> e.name().equals("runtime.json") ? e : e.withDeclaredSize(oneGiB));
        Path packageFile = temp.resolve("total-bomb.zip");
        Files.write(packageFile, TestRuntimePackages.zip(entries));
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instanceRoot());
        assertFailedReason(result, DirectCefRuntimeFailureReason.PACKAGE_INVALID);
    }

    @Test void optionalPackageHashIsVerifiedBeforeInstall() throws Exception {
        Path instance = instanceRoot();
        Map<String, byte[]> files = defaultFiles();
        Path packageFile = temp.resolve("hashed.zip");
        byte[] zip = TestRuntimePackages.packageZip(files);
        Files.write(packageFile, zip);
        String goodHash = TestRuntimePackages.sha256(zip);
        RuntimeImportResult good = DirectCefRuntimePackageImporter.importPackage(packageFile, instance,
                new DirectCefRuntimePackageImporter.Options(null, goodHash, null, null, 0));
        assertEquals(RuntimeImportResult.Status.INSTALLED, good.status(), failureText(good));
        assertNoInstallation(temp.resolve("other-instance"));

        Path other = temp.resolve("other-instance");
        String badHash = "0".repeat(64);
        RuntimeImportResult bad = DirectCefRuntimePackageImporter.importPackage(packageFile, other,
                new DirectCefRuntimePackageImporter.Options(null, badHash, null, null, 0));
        assertFailedReason(bad, DirectCefRuntimeFailureReason.PACKAGE_HASH_MISMATCH);
        assertNoInstallation(other);
    }

    @Test void cancelledImportCleansStagingAndNeverPublishes() throws Exception {
        Path instance = instanceRoot();
        Map<String, byte[]> files = defaultFiles();
        files.put("big.bin", randomBytes(4 * 1024 * 1024));
        Path packageFile = writePackage(files);
        TestToken cancel = new TestToken();
        CountDownLatch extractionStarted = new CountDownLatch(1);
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instance,
                new DirectCefRuntimePackageImporter.Options(null, null, p -> {
                    if (p.phase() == RuntimeImportProgress.Phase.EXTRACTING && p.filesCompleted() >= 1) {
                        cancel.cancel();
                        extractionStarted.countDown();
                    }
                }, cancel, 5000));
        assertEquals(RuntimeImportResult.Status.CANCELLED, result.status());
        assertNoInstallation(instance);
        assertEquals(List.of(), leftoverSiblings(DirectCefRuntimeDiscovery.standardDirectory(instance)));
    }

    @Test void failedMidExtractionLeavesNoFinalAndCleansStaging() throws Exception {
        Path instance = instanceRoot();
        Map<String, byte[]> files = defaultFiles();
        files.put("big.bin", randomBytes(4 * 1024 * 1024));
        String manifest = TestRuntimePackages.manifestJson(files)
                .replaceFirst("[0-9a-f]{64}", "f".repeat(64)); // big.bin hash is wrong
        Path packageFile = temp.resolve("mid-fail.zip");
        Files.write(packageFile, TestRuntimePackages.packageZip(files, manifest));
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instance);
        assertFailedReason(result, DirectCefRuntimeFailureReason.HASH_MISMATCH);
        assertNoInstallation(instance);
        DirectCefRuntimeDiscovery.Probe probe = DirectCefRuntimeDiscovery.probe(instance, (Path) null);
        assertEquals(DirectCefRuntimeFailureReason.NOT_FOUND, probe.failure().reason());
        assertEquals(List.of(), leftoverSiblings(DirectCefRuntimeDiscovery.standardDirectory(instance)));
    }

    @Test void lockHeldElsewhereYieldsInstallInProgressThenSucceeds() throws Exception {
        Path instance = instanceRoot();
        Path packageFile = writePackage(defaultFiles());
        DirectCefRuntimeRequirement requirement = DirectCefRuntimeRequirement.required();
        Path lockFile = DirectCefRuntimePackageImporter.lockFilePath(instance, requirement);
        Files.createDirectories(lockFile.getParent());
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock held = channel.tryLock()) {
            assertNotNull(held, "test lock could not be acquired");
            RuntimeImportResult busy = DirectCefRuntimePackageImporter.importPackage(packageFile, instance,
                    new DirectCefRuntimePackageImporter.Options(null, null, null, null, 300));
            assertEquals(RuntimeImportResult.Status.INSTALL_IN_PROGRESS, busy.status());
            assertEquals(DirectCefRuntimeFailureReason.INSTALL_IN_PROGRESS, busy.failure().reason());
            assertNoInstallation(instance);
        }
        RuntimeImportResult later = DirectCefRuntimePackageImporter.importPackage(packageFile, instance);
        assertEquals(RuntimeImportResult.Status.INSTALLED, later.status(), failureText(later));
    }

    @Test void concurrentImportsProduceOneValidRuntime() throws Exception {
        Path instance = instanceRoot();
        Map<String, byte[]> files = defaultFiles();
        files.put("big.bin", randomBytes(16 * 1024 * 1024));
        Path packageFile = writePackage(files);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RuntimeImportResult> first = pool.submit(() -> DirectCefRuntimePackageImporter
                    .importPackage(packageFile, instance));
            Future<RuntimeImportResult> second = pool.submit(() -> DirectCefRuntimePackageImporter
                    .importPackage(packageFile, instance));
            RuntimeImportResult a = first.get(120, TimeUnit.SECONDS);
            RuntimeImportResult b = second.get(120, TimeUnit.SECONDS);
            Set<RuntimeImportResult.Status> allowed = Set.of(RuntimeImportResult.Status.INSTALLED,
                    RuntimeImportResult.Status.ALREADY_INSTALLED, RuntimeImportResult.Status.INSTALL_IN_PROGRESS);
            assertTrue(allowed.contains(a.status()), "unexpected first result: " + a);
            assertTrue(allowed.contains(b.status()), "unexpected second result: " + b);
            assertTrue(a.status() == RuntimeImportResult.Status.INSTALLED
                            || b.status() == RuntimeImportResult.Status.INSTALLED,
                    "neither concurrent import installed the runtime");
            DirectCefRuntimeDiscovery.Probe probe = DirectCefRuntimeDiscovery.probe(instance, (Path) null);
            assertTrue(probe.valid(), "concurrent imports left no valid runtime: " + probe.failure());
            assertEquals(List.of(), leftoverSiblings(DirectCefRuntimeDiscovery.standardDirectory(instance)));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void corruptExistingRuntimeIsRepairedThroughQuarantine() throws Exception {
        Path instance = instanceRoot();
        Path standard = DirectCefRuntimeDiscovery.standardDirectory(instance);
        writeRuntimeTree(standard, defaultFiles());
        Files.write(standard.resolve("libcef.dll"), TestRuntimePackages.utf8("xyz"));
        DirectCefRuntimeDiscovery.Probe before = DirectCefRuntimeDiscovery.probe(instance, (Path) null);
        assertFalse(before.valid());
        assertEquals(DirectCefRuntimeFailureReason.HASH_MISMATCH, before.failure().reason());

        Path packageFile = writePackage(defaultFiles());
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instance);
        assertEquals(RuntimeImportResult.Status.INSTALLED, result.status(), failureText(result));
        DirectCefRuntimeDiscovery.Probe after = DirectCefRuntimeDiscovery.probe(instance, (Path) null);
        assertTrue(after.valid(), "repair left an invalid runtime: " + after.failure());
        assertEquals(List.of(), leftoverSiblings(standard));
    }

    @Test void publishFailureRollsBackToTheQuarantinedRuntime() throws Exception {
        Path instance = instanceRoot();
        Path standard = DirectCefRuntimeDiscovery.standardDirectory(instance);
        writeRuntimeTree(standard, defaultFiles());
        Files.write(standard.resolve("libcef.dll"), TestRuntimePackages.utf8("xyz"));
        DirectCefRuntimePackageImporter.publishInterceptor = (staging, finalDir) -> {
            throw new java.io.IOException("injected publish failure");
        };
        Path packageFile = writePackage(defaultFiles());
        try {
            RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instance);
            assertFailedReason(result, DirectCefRuntimeFailureReason.IO_ERROR);
            String message = result.failure().getMessage();
            assertTrue(message.contains("staging="), "error must name the staging path: " + message);
            assertTrue(message.contains("quarantine="), "error must name the quarantine path: " + message);
            assertTrue(message.contains("final="), "error must name the final path: " + message);
            // The old (corrupt) runtime must be back at the final location; nothing half-published.
            assertArrayEquals(TestRuntimePackages.utf8("xyz"),
                    Files.readAllBytes(standard.resolve("libcef.dll")));
            assertEquals(List.of(), leftoverSiblings(standard));
        } finally {
            DirectCefRuntimePackageImporter.publishInterceptor = null;
        }
    }

    @Test void loadedRuntimeIsNeverReplaced() throws Exception {
        Path instance = instanceRoot();
        Path standard = DirectCefRuntimeDiscovery.standardDirectory(instance);
        writeRuntimeTree(standard, defaultFiles());
        ValidatedDirectCefRuntime validated = DirectCefRuntimeValidator.validate(standard);
        DirectCefNativeLoader.guard(validated);
        Files.delete(standard.resolve("libcef.dll"));
        DirectCefRuntimeDiscovery.Probe before = DirectCefRuntimeDiscovery.probe(instance, (Path) null);
        assertFalse(before.valid());
        assertEquals(DirectCefRuntimeFailureReason.MISSING_FILE, before.failure().reason());

        Path packageFile = writePackage(defaultFiles());
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instance);
        assertFailedReason(result, DirectCefRuntimeFailureReason.RUNTIME_IN_USE);
        assertFalse(Files.exists(standard.resolve("libcef.dll")), "loaded runtime must stay untouched");
    }

    @Test void packageBuildIsDeterministic() throws Exception {
        Map<String, byte[]> files = defaultFiles();
        files.put("locales/zh-CN.pak", TestRuntimePackages.utf8("zh"));
        byte[] first = TestRuntimePackages.packageZip(files);
        byte[] second = TestRuntimePackages.packageZip(files);
        assertArrayEquals(first, second, "identical input must produce identical package bytes");
    }

    @Test void setupMessagesCoverEveryReason() {
        for (DirectCefRuntimeFailureReason reason : DirectCefRuntimeFailureReason.values()) {
            assertFalse(DirectCefRuntimeSetupMessages.messageFor(reason).isBlank(), reason.name());
        }
        assertFalse(DirectCefRuntimeSetupMessages.canImport(DirectCefRuntimeFailureReason.WRONG_PLATFORM));
        assertFalse(DirectCefRuntimeSetupMessages.canImport(DirectCefRuntimeFailureReason.WRONG_ARCH));
        assertFalse(DirectCefRuntimeSetupMessages.canImport(DirectCefRuntimeFailureReason.RUNTIME_IN_USE));
        assertTrue(DirectCefRuntimeSetupMessages.canImport(DirectCefRuntimeFailureReason.NOT_FOUND));
        assertTrue(DirectCefRuntimeSetupMessages.canImport(DirectCefRuntimeFailureReason.HASH_MISMATCH));
    }

    // ------------------------------------------------------------------ helpers

    private static final class TestToken implements DirectCefRuntimePackageImporter.CancellationToken {
        private volatile boolean cancelled;
        void cancel() { cancelled = true; }
        @Override public boolean isCancelled() { return cancelled; }
    }

    private Path instanceRoot() { return temp.resolve("instance"); }

    private Map<String, byte[]> defaultFiles() {
        return TestRuntimePackages.runtimeFiles(
                Map.of("locales/en-US.pak", TestRuntimePackages.utf8("pak")));
    }

    private Path writePackage(Map<String, byte[]> files) throws Exception {
        Path packageFile = temp.resolve("runtime-" + UUID.randomUUID() + ".zip");
        Files.write(packageFile, TestRuntimePackages.packageZip(files));
        return packageFile;
    }

    private void assertPackageReason(String manifestJson, DirectCefRuntimeFailureReason reason) throws Exception {
        Map<String, byte[]> files = defaultFiles();
        Path packageFile = temp.resolve("reason-" + UUID.randomUUID() + ".zip");
        Files.write(packageFile, TestRuntimePackages.packageZip(files, manifestJson));
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, temp.resolve("instance-" + UUID.randomUUID()));
        assertFailedReason(result, reason);
    }

    private void assertEntryReason(Map<String, byte[]> files, String manifest, String entryName,
                                   DirectCefRuntimeFailureReason reason) throws Exception {
        List<TestRuntimePackages.Entry> entries = TestRuntimePackages.entries(files, manifest);
        entries.add(new TestRuntimePackages.Entry(entryName, TestRuntimePackages.utf8("x")));
        Path packageFile = temp.resolve("entry-" + UUID.randomUUID() + ".zip");
        Files.write(packageFile, TestRuntimePackages.zip(entries));
        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, temp.resolve("instance-" + UUID.randomUUID()));
        assertFailedReason(result, reason);
    }

    private static void writeRuntimeTree(Path root, Map<String, byte[]> files) throws Exception {
        Files.createDirectories(root);
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Path target = root.resolve(file.getKey().replace('/', java.io.File.separatorChar));
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }
        Files.write(root.resolve("runtime.json"),
                TestRuntimePackages.manifestJson(files).getBytes(StandardCharsets.UTF_8));
    }

    private static void assertFailedReason(RuntimeImportResult result, DirectCefRuntimeFailureReason reason) {
        assertEquals(RuntimeImportResult.Status.FAILED, result.status(), failureText(result));
        assertNotNull(result.failure(), "failure must be structured");
        assertEquals(reason, result.failure().reason(), failureText(result));
    }

    private static String failureText(RuntimeImportResult result) {
        return result.failure() == null ? result.toString() : result.failure().getMessage();
    }

    private static void assertNoInstallation(Path instance) throws Exception {
        Path standard = DirectCefRuntimeDiscovery.standardDirectory(instance);
        assertFalse(Files.exists(standard), "runtime must not exist at: " + standard);
        DirectCefRuntimeDiscovery.Probe probe = DirectCefRuntimeDiscovery.probe(instance, (Path) null);
        assertFalse(probe.valid(), "staging must never be discoverable");
    }

    private static List<String> leftoverSiblings(Path standard) throws Exception {
        if (standard.getParent() == null || !Files.isDirectory(standard.getParent())) return List.of();
        List<String> leftovers = new ArrayList<>();
        try (var stream = Files.list(standard.getParent())) {
            stream.filter(path -> !path.equals(standard)).forEach(path -> leftovers.add(path.getFileName().toString()));
        }
        return leftovers;
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return bytes;
    }
}
