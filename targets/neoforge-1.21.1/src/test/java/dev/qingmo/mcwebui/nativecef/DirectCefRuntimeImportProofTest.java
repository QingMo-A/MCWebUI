package dev.qingmo.mcwebui.nativecef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase B integration proof, driven by scripts/direct-cef-runtime/test-runtime-import.ps1.
 * Enabled only when -PmcwebuiProofPackage / -PmcwebuiProofInstanceRoot are supplied,
 * so a normal {@code gradlew test} run skips it.
 */
@EnabledIfSystemProperty(named = "mcwebui.proof.package", matches = ".+")
class DirectCefRuntimeImportProofTest {

    @Test void importedPackageIsRediscoveredThroughTheStandardPath() throws Exception {
        Path packageFile = Path.of(System.getProperty("mcwebui.proof.package"));
        String instanceRootValue = System.getProperty("mcwebui.proof.instanceRoot", "");
        assertFalse(instanceRootValue.isBlank(), "mcwebui.proof.instanceRoot is required");
        Path instanceRoot = Path.of(instanceRootValue).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(packageFile), "proof package is missing: " + packageFile);

        Path standard = DirectCefRuntimeDiscovery.standardDirectory(instanceRoot);
        assertFalse(Files.exists(standard),
                "the proof instance must start without the standard runtime: " + standard);

        RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packageFile, instanceRoot);
        assertTrue(result.success(), "import failed: " + result);
        assertEquals(RuntimeImportResult.Status.INSTALLED, result.status());
        assertEquals(standard, result.finalDirectory());

        // The final proof of Phase B: the installed runtime is found by the normal
        // Phase A standard discovery, with no runtimeDir override anywhere.
        ValidatedDirectCefRuntime rediscovered = DirectCefRuntimeDiscovery.discover(instanceRoot, (Path) null);
        assertEquals(ValidatedDirectCefRuntime.Source.STANDARD, rediscovered.source());
        assertEquals(standard, rediscovered.directory());
        DirectCefRuntimeManifest manifest =
                DirectCefRuntimeManifestParser.parse(standard.resolve("runtime.json"));
        assertTrue(manifest.files().size() > 200,
                "expected a full CEF runtime manifest, got " + manifest.files().size());
        assertEquals(DirectCefRuntimeRequirement.RUNTIME_ID, manifest.runtimeId());

        String evidence = System.getProperty("mcwebui.proof.evidence", "");
        if (!evidence.isBlank()) {
            Path evidencePath = Path.of(evidence);
            Files.createDirectories(evidencePath.getParent());
            String json = "{\"schemaVersion\":1,\"status\":\"PASS\",\"importStatus\":\"" + result.status()
                    + "\",\"package\":\"" + jsonEscape(packageFile) + "\",\"instanceRoot\":\""
                    + jsonEscape(instanceRoot) + "\",\"runtimeDirectory\":\"" + jsonEscape(standard)
                    + "\",\"manifestFiles\":" + manifest.files().size() + ",\"runtimeId\":\""
                    + manifest.runtimeId() + "\"}\n";
            Files.writeString(evidencePath, json, StandardCharsets.UTF_8);
        }
    }

    private static String jsonEscape(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
