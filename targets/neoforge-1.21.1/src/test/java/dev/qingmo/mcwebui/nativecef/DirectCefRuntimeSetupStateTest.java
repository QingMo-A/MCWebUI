package dev.qingmo.mcwebui.nativecef;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pure setup regressions independent of Minecraft screen construction. */
class DirectCefRuntimeSetupStateTest {
    @TempDir Path temp;

    @AfterEach void resetLoader() { DirectCefNativeLoader.resetForTests(); }

    @Test void invalidOverrideRemainsAuthoritativeEvenWithValidStandard() throws Exception {
        Path instance = temp.resolve("instance");
        byte[] runtimePackage = TestRuntimePackages.packageZip(TestRuntimePackages.runtimeFiles(
                Map.of("locales/en-US.pak", TestRuntimePackages.utf8("pak"))));
        Path packageFile = temp.resolve("runtime.zip");
        Files.write(packageFile, runtimePackage);
        assertTrue(DirectCefRuntimePackageImporter.importPackage(packageFile, instance).success());
        Path invalidOverride = temp.resolve("override-does-not-exist");
        DirectCefRuntimeDiscovery.Probe standard = DirectCefRuntimeDiscovery.probe(instance, (Path) null);
        DirectCefRuntimeDiscovery.Probe override = DirectCefRuntimeDiscovery.probe(instance, invalidOverride);
        assertTrue(standard.valid());
        assertFalse(override.valid());
        DirectCefRuntimeSetupState state = DirectCefRuntimeSetupState.initial(invalidOverride, override, null);
        assertTrue(state.developerOverrideError());
        assertFalse(state.importAvailable(true));
        assertFalse(state.downloadAvailable());
        assertFalse(state.offlineImportAvailable());
        assertEquals("DEVELOPER OVERRIDE ERROR", state.statusMessage());
    }

    @Test void missingStandardWithoutOverrideKeepsOfflineImportAndNoDownload() throws Exception {
        Path instance = temp.resolve("missing");
        DirectCefRuntimeDiscovery.Probe probe = DirectCefRuntimeDiscovery.probe(instance, (Path) null);
        DirectCefRuntimeSetupState state = DirectCefRuntimeSetupState.initial(null, probe, null);
        assertFalse(state.developerOverrideError());
        assertTrue(state.offlineImportAvailable());
        assertTrue(state.importAvailable(true));
        assertFalse(state.downloadAvailable());
        assertFalse(state.continueAvailable());
        Path installedInstance = temp.resolve("installed");
        Path packageFile = temp.resolve("offline-runtime.zip");
        Files.write(packageFile, TestRuntimePackages.packageZip(TestRuntimePackages.runtimeFiles(
                Map.of("locales/en-US.pak", TestRuntimePackages.utf8("pak")))));
        RuntimeImportResult imported = DirectCefRuntimePackageImporter.importPackage(packageFile, installedInstance);
        assertTrue(imported.success());
        DirectCefRuntimeSetupState installed = DirectCefRuntimeSetupState.initial(null,
                DirectCefRuntimeDiscovery.probe(installedInstance, (Path) null), null);
        assertTrue(installed.continueAvailable());
    }

    @Test void lifecycleCancelDisposeIsIdempotentAndRejectsStaleUiCallbacks() {
        RuntimeSetupJobLifecycle lifecycle = new RuntimeSetupJobLifecycle();
        assertTrue(lifecycle.begin());
        assertTrue(lifecycle.isRunning());
        lifecycle.finish();
        assertFalse(lifecycle.isRunning());
        assertTrue(lifecycle.acceptsUiCallback());
        assertFalse(lifecycle.isExecutorShutdown());
        assertTrue(lifecycle.begin());
        lifecycle.cancel();
        assertTrue(lifecycle.isCancelled());
        lifecycle.dispose();
        lifecycle.dispose();
        assertTrue(lifecycle.isDisposed());
        assertFalse(lifecycle.isRunning());
        assertFalse(lifecycle.acceptsUiCallback());
        assertTrue(lifecycle.isExecutorShutdown());
        assertFalse(lifecycle.begin());
    }

    @Test void extractingImportSnapshotRemainsVisibleInUnifiedProgress() {
        RuntimeImportProgress extracting = new RuntimeImportProgress(
                RuntimeImportProgress.Phase.EXTRACTING, "Extracting locales/en-US.pak",
                "locales/en-US.pak", 2, 10, 512, 4096);
        RuntimeDownloadProgress visible = RuntimeDownloadProgress.installing(extracting, 8192);
        assertEquals(RuntimeDownloadProgress.Phase.INSTALLING, visible.phase());
        assertSame(extracting, visible.importProgress());
        assertEquals(RuntimeImportProgress.Phase.EXTRACTING, visible.importProgress().phase());
        assertEquals(512, visible.bytesDownloaded());
    }
}
