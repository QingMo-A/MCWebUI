package dev.qingmo.mcwebui.target.neoforge1211;

import com.mojang.logging.LogUtils;
import dev.qingmo.mcwebui.nativecef.DirectCefRuntimeDiscovery;
import dev.qingmo.mcwebui.nativecef.DirectCefRuntimeException;
import dev.qingmo.mcwebui.nativecef.DirectCefRuntimeFailureReason;
import dev.qingmo.mcwebui.nativecef.DirectCefRuntimePackageImporter;
import dev.qingmo.mcwebui.nativecef.DirectCefRuntimeRequirement;
import dev.qingmo.mcwebui.nativecef.DirectCefRuntimeSetupMessages;
import dev.qingmo.mcwebui.nativecef.RuntimeImportProgress;
import dev.qingmo.mcwebui.nativecef.RuntimeImportResult;
import dev.qingmo.mcwebui.nativecef.RuntimeDownloadProgress;
import dev.qingmo.mcwebui.nativecef.RuntimeDownloadResult;
import dev.qingmo.mcwebui.nativecef.DirectCefRuntimeReleaseDescriptor;
import dev.qingmo.mcwebui.nativecef.DirectCefRuntimeDownloader;
import dev.qingmo.mcwebui.nativecef.DirectCefRuntimeSetupState;
import dev.qingmo.mcwebui.nativecef.RuntimeSetupJobLifecycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ordinary Minecraft Screen for installing the Direct CEF runtime offline.
 * This screen deliberately does not use the Web UI: it must work when the CEF
 * runtime itself is missing. Import runs on a worker thread; the client thread
 * only starts the job and reads immutable progress snapshots.
 */
public final class DirectCefRuntimeSetupScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int COLOR_WHITE = 0xFFFFFF;
    private static final int COLOR_GRAY = 0xAAAAAA;
    private static final int COLOR_RED = 0xFF5555;
    private static final int COLOR_GREEN = 0x55FF55;
    private static String rememberedPackagePath = "";

    private final Path instanceRoot;
    private final DirectCefRuntimeRequirement requirement;
    private final Path expectedDirectory;
    private final Screen previousScreen;
    private final Runnable onContinue;
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MCWebUI Direct CEF Runtime Import");
        thread.setDaemon(true);
        return thread;
    });

    private DirectCefRuntimeException lastFailure;
    private EditBox packagePathBox;
    private Button importButton;
    private Button downloadButton;
    private Button retryButton;
    private Button openFolderButton;
    private Button cancelButton;
    private Button continueButton;
    private ImportJob job;
    private volatile boolean installed;
    private volatile boolean importDisabled;
    private String statusMessage;
    private final boolean overrideConfigured;
    private final boolean developerOverrideError;
    private final DirectCefRuntimeReleaseDescriptor releaseDescriptor;
    private final DirectCefRuntimeDownloader downloader;
    private final DirectCefRuntimeSetupState setupState;
    private volatile boolean disposed;

    public DirectCefRuntimeSetupScreen(Path instanceRoot, DirectCefRuntimeDiscovery.Probe probe,
                                       Screen previousScreen, Runnable onContinue) {
        // Production currently has no published runtime asset; download is
        // enabled only by the injectable descriptor constructor used by a
        // future project-owned release configuration and deterministic tests.
        this(instanceRoot, probe, previousScreen, onContinue, null,
                new DirectCefRuntimeDownloader());
    }

    /** Injectable descriptor/downloader constructor used by deterministic setup tests. */
    public DirectCefRuntimeSetupScreen(Path instanceRoot, DirectCefRuntimeDiscovery.Probe probe,
                                       Screen previousScreen, Runnable onContinue,
                                       DirectCefRuntimeReleaseDescriptor releaseDescriptor,
                                       DirectCefRuntimeDownloader downloader) {
        super(Component.literal("MCWebUI Browser Runtime Required"));
        this.instanceRoot = Objects.requireNonNull(instanceRoot, "instanceRoot").toAbsolutePath().normalize();
        this.requirement = DirectCefRuntimeRequirement.required();
        this.expectedDirectory = DirectCefRuntimeDiscovery.standardDirectory(this.instanceRoot, requirement);
        this.previousScreen = previousScreen;
        this.onContinue = Objects.requireNonNull(onContinue, "onContinue");
        this.releaseDescriptor = releaseDescriptor;
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.overrideConfigured = configuredOverride() != null;
        this.setupState = DirectCefRuntimeSetupState.initial(configuredOverride(), probe, releaseDescriptor);
        this.developerOverrideError = setupState.developerOverrideError();
        String prefilled = System.getProperty("mcwebui.directCef.package", "").trim();
        if (!prefilled.isEmpty()) rememberedPackagePath = prefilled;
        this.installed = setupState.installed();
        this.lastFailure = setupState.failure();
        this.importDisabled = !setupState.offlineImportAvailable();
        this.statusMessage = setupState.statusMessage();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        packagePathBox = new EditBox(font, (width - 340) / 2, 108, 340, 20,
                Component.literal("Runtime package path"));
        packagePathBox.setMaxLength(4096);
        packagePathBox.setValue(rememberedPackagePath);
        packagePathBox.setResponder(value -> updateButtons());
        addRenderableWidget(packagePathBox);
        int rowY = 150;
        importButton = addRenderableWidget(Button.builder(Component.literal("Import Package"), button -> startImport())
                .bounds((width - 110) / 2, rowY + 24, 110, 20).build());
        downloadButton = addRenderableWidget(Button.builder(Component.literal("Download & Install"), button -> startDownload())
                .bounds((width - 130) / 2, rowY, 130, 20).build());
        retryButton = addRenderableWidget(Button.builder(Component.literal("Retry"), button -> retry())
                .bounds(buttonX(0), rowY + 48, 70, 20).build());
        openFolderButton = addRenderableWidget(Button.builder(Component.literal("Open Runtime Folder"), button -> openRuntimeFolder())
                .bounds(buttonX(1), rowY + 48, 120, 20).build());
        cancelButton = addRenderableWidget(Button.builder(Component.literal("Close"), button -> cancel())
                .bounds(buttonX(2), rowY + 48, 70, 20).build());
        continueButton = addRenderableWidget(Button.builder(Component.literal("Continue"), button -> continueToBrowser())
                .bounds((width - 90) / 2, rowY, 90, 20).build());
        updateButtons();
    }

    @Override public void tick() {
        updateButtons();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, Component.literal("MCWebUI Browser Runtime Required"),
                width / 2, 24, COLOR_WHITE);
        graphics.drawCenteredString(font, Component.literal("Required: CEF " + requirement.cefVersion()
                        + " (Chromium " + requirement.chromiumVersion() + ") - "
                        + displayPlatform() + " " + requirement.arch()),
                width / 2, 38, COLOR_GRAY);
        graphics.drawCenteredString(font, Component.literal("Status: " + statusMessage),
                width / 2, 52, statusColor());
        graphics.drawCenteredString(font, Component.literal((developerOverrideError
                        ? "Standard directory (ignored while override is set): " : "Expected directory: ")
                        + truncate(expectedDirectory.toString())),
                width / 2, 66, COLOR_GRAY);
        if (overrideConfigured) {
            graphics.drawCenteredString(font, Component.literal(developerOverrideError
                            ? "An explicit Direct CEF runtime override is configured but is invalid."
                            : "Note: an explicit runtime override is configured; imported packages install into the standard directory"),
                    width / 2, 80, COLOR_RED);
            if (developerOverrideError) {
                graphics.drawCenteredString(font, Component.literal("Override: " + truncate(String.valueOf(configuredOverride()))),
                        width / 2, 94, COLOR_GRAY);
                graphics.drawCenteredString(font, Component.literal(
                                "Fix or remove -Dmcwebui.directCef.runtimeDir=... and restart/retry."),
                        width / 2, 120, COLOR_GRAY);
            }
        }
        if (lastFailure != null) {
            graphics.drawCenteredString(font, Component.literal("Reason: " + lastFailure.reason().name()),
                    width / 2, developerOverrideError ? 108 : 94, COLOR_GRAY);
        }
        if (!developerOverrideError) {
            graphics.drawCenteredString(font, Component.literal("Runtime package path (ZIP):"),
                    width / 2, 98, COLOR_GRAY);
            if (!setupState.downloadAvailable()) {
                graphics.drawCenteredString(font, Component.literal(
                                "Automatic download is not configured; use offline import."),
                        width / 2, 132, COLOR_GRAY);
            }
        }
        renderProgress(graphics);
    }

    private void renderProgress(GuiGraphics graphics) {
        ImportJob current = job;
        RuntimeDownloadProgress snapshot = current == null ? null : current.progress;
        if (current == null || snapshot == null) return;
        if (current.running) {
            graphics.drawCenteredString(font, Component.literal(downloadPhaseLabel(snapshot.phase())),
                    width / 2, 228, COLOR_WHITE);
            RuntimeImportProgress importSnapshot = snapshot.importProgress();
            if (importSnapshot != null && importSnapshot.currentFile() != null) {
                graphics.drawCenteredString(font, Component.literal(importSnapshot.currentFile()
                                + " (" + importSnapshot.filesCompleted() + "/" + importSnapshot.filesTotal() + ")"),
                        width / 2, 240, COLOR_GRAY);
                graphics.drawCenteredString(font, Component.literal(megabytes(importSnapshot.bytesExtracted())
                                + " / " + megabytes(importSnapshot.bytesExpected())),
                        width / 2, 252, COLOR_GRAY);
            } else if (snapshot.bytesTotal() > 0) {
                graphics.drawCenteredString(font, Component.literal(megabytes(snapshot.bytesDownloaded())
                                + " / " + megabytes(snapshot.bytesTotal())), width / 2, 240, COLOR_GRAY);
            }
        } else if (snapshot.phase() == RuntimeDownloadProgress.Phase.FAILED
                || snapshot.phase() == RuntimeDownloadProgress.Phase.CANCELLED) {
            graphics.drawCenteredString(font, Component.literal(downloadPhaseLabel(snapshot.phase())),
                    width / 2, 228, COLOR_RED);
        }
    }

    private void startImport() {
        ImportJob current = job;
        if (current != null && current.running) return;
        String value = packagePathBox.getValue().trim();
        if (value.isEmpty()) return;
        rememberedPackagePath = value;
        Path packagePath = Path.of(value);
        if (!Files.isRegularFile(packagePath)) {
            statusMessage = "Package file was not found: " + value;
            return;
        }
        ImportJob newJob = new ImportJob(packagePath, importExecutor);
        job = newJob;
        if (!newJob.lifecycle.begin()) return;
        newJob.progress = RuntimeDownloadProgress.simple(RuntimeDownloadProgress.Phase.INSTALLING,
                "Preparing runtime import", 0);
        statusMessage = "Importing runtime package...";
        updateButtons();
        newJob.lifecycle.execute(() -> {
            RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packagePath, instanceRoot,
                    new DirectCefRuntimePackageImporter.Options(requirement, null,
                            p -> newJob.progress = RuntimeDownloadProgress.installing(p, p.bytesExpected()),
                            newJob.lifecycle,
                            DirectCefRuntimePackageImporter.DEFAULT_LOCK_TIMEOUT_MILLIS));
            newJob.lifecycle.finish();
            Minecraft.getInstance().execute(() -> { if (!disposed && newJob.lifecycle.acceptsUiCallback()) onImportFinished(newJob, result); });
        });
    }

    private void startDownload() {
        if (releaseDescriptor == null || !releaseDescriptor.sourceConfigured()) return;
        ImportJob current = job;
        if (current != null && current.running) return;
        ImportJob newJob = new ImportJob(null, importExecutor);
        job = newJob;
        if (!newJob.lifecycle.begin()) return;
        statusMessage = "Downloading runtime package...";
        updateButtons();
        newJob.lifecycle.execute(() -> {
            RuntimeDownloadResult result = downloader.download(releaseDescriptor, instanceRoot,
                    p -> newJob.progress = p, newJob.lifecycle);
            newJob.lifecycle.finish();
            Minecraft.getInstance().execute(() -> { if (!disposed && newJob.lifecycle.acceptsUiCallback()) onDownloadFinished(newJob, result); });
        });
    }

    private void onImportFinished(ImportJob finished, RuntimeImportResult result) {
        finished.running = false;
        switch (result.status()) {
            case INSTALLED, ALREADY_INSTALLED -> {
                DirectCefRuntimeDiscovery.Probe probe = DirectCefRuntimeDiscovery.probe(
                        instanceRoot, configuredOverride(), requirement);
                if (probe.valid()) {
                    setupState.markInstalled();
                    syncState();
                    LOGGER.info("MCWebUI Direct CEF runtime installed at {}", result.finalDirectory());
                } else {
                    setupState.applyProbe(probe);
                    syncState();
                }
            }
            case CANCELLED -> statusMessage = "Import cancelled.";
            case INSTALL_IN_PROGRESS -> statusMessage =
                    "Another process is already installing this runtime. Wait and retry.";
            case FAILED -> {
                lastFailure = result.failure();
                setupState.markFailure(result.failure());
                syncState();
                LOGGER.error("MCWebUI Direct CEF runtime package import failed: {}",
                        result.failure().getMessage(), result.failure());
            }
        }
        updateButtons();
    }

    private void onDownloadFinished(ImportJob finished, RuntimeDownloadResult result) {
        finished.running = false;
        if (result.success()) {
            DirectCefRuntimeDiscovery.Probe probe = DirectCefRuntimeDiscovery.probe(
                    instanceRoot, configuredOverride(), requirement);
            if (probe.valid()) {
                setupState.markInstalled();
                syncState();
            } else {
                setupState.applyProbe(probe);
                syncState();
            }
        } else if (result.status() == RuntimeDownloadResult.Status.CANCELLED) {
            statusMessage = "Download cancelled.";
        } else {
            lastFailure = result.failure();
            statusMessage = DirectCefRuntimeSetupMessages.messageFor(
                    result.failure() == null ? DirectCefRuntimeFailureReason.DOWNLOAD_IO_ERROR : result.failure().reason());
        }
        updateButtons();
    }

    private void retry() {
        DirectCefRuntimeDiscovery.Probe probe = DirectCefRuntimeDiscovery.probe(
                instanceRoot, configuredOverride(), requirement);
            if (probe.valid()) {
            setupState.markInstalled();
            syncState();
        } else {
            setupState.applyProbe(probe);
            syncState();
        }
        updateButtons();
    }

    private void continueToBrowser() {
        onContinue.run();
    }

    private void cancel() {
        ImportJob current = job;
        if (current != null && current.running) {
            current.lifecycle.cancel();
            cancelButton.active = false;
            statusMessage = "Cancelling...";
            return;
        }
        minecraft.setScreen(previousScreen);
    }

    private void openRuntimeFolder() {
        try {
            Path cefRoot;
            if (developerOverrideError && configuredOverride() != null) {
                cefRoot = configuredOverride();
                while (cefRoot != null && !Files.isDirectory(cefRoot)) cefRoot = cefRoot.getParent();
                if (cefRoot == null) {
                    statusMessage = "Override folder does not exist: " + configuredOverride();
                    return;
                }
            } else {
                cefRoot = instanceRoot.resolve("mcwebui").resolve("runtime").resolve("cef");
                Files.createDirectories(cefRoot);
            }
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                statusMessage = "Opening folders is only supported on Windows.";
                return;
            }
            new ProcessBuilder("explorer.exe", cefRoot.toString()).start();
        } catch (Exception ex) {
            LOGGER.warn("Unable to open the Direct CEF runtime folder", ex);
            statusMessage = "Unable to open the runtime folder; see the log.";
        }
    }

    private void updateButtons() {
        boolean running = job != null && job.running;
        if (packagePathBox != null) packagePathBox.visible = !developerOverrideError;
        boolean packagePresent = packagePathBox != null && !packagePathBox.getValue().isBlank();
        importButton.active = !running && setupState.importAvailable(packagePresent);
        downloadButton.active = !running && setupState.downloadAvailable();
        retryButton.active = !running;
        openFolderButton.active = !running;
        openFolderButton.setMessage(Component.literal(developerOverrideError ? "Open Override Folder" : "Open Runtime Folder"));
        cancelButton.active = true;
        cancelButton.setMessage(Component.literal(running ? "Cancel" : "Close"));
        importButton.visible = !installed && !developerOverrideError;
        downloadButton.visible = !installed && !developerOverrideError && setupState.downloadAvailable();
        retryButton.visible = !installed;
        openFolderButton.visible = !installed;
        cancelButton.visible = !installed;
        continueButton.visible = installed;
    }

    private int statusColor() {
        if (installed) return COLOR_GREEN;
        if (lastFailure != null) return COLOR_RED;
        return COLOR_GRAY;
    }

    private int buttonX(int index) {
        int totalWidth = 70 + 120 + 70 + 2 * 8;
        int start = (width - totalWidth) / 2;
        return start + switch (index) {
            case 0 -> 0;
            case 1 -> 70 + 8;
            default -> 70 + 8 + 120 + 8;
        };
    }

    private String truncate(String value) {
        return font.plainSubstrByWidth(value, Math.max(80, width - 40));
    }

    private String displayPlatform() {
        String platform = requirement.platform();
        return platform.isEmpty() ? platform
                : Character.toUpperCase(platform.charAt(0)) + platform.substring(1);
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String downloadPhaseLabel(RuntimeDownloadProgress.Phase phase) {
        return switch (phase) {
            case CONNECTING -> "Connecting...";
            case DOWNLOADING -> "Downloading runtime...";
            case VERIFYING -> "Verifying download...";
            case INSTALLING -> "Installing runtime...";
            case COMPLETE -> "Complete.";
            case FAILED -> "Download failed.";
            case CANCELLED -> "Cancelled.";
        };
    }

    private static Path configuredOverride() {
        String value = System.getProperty(DirectCefRuntimeDiscovery.RUNTIME_OVERRIDE_PROPERTY, "").trim();
        return value.isEmpty() ? null : Path.of(value);
    }

    private void syncState() {
        installed = setupState.installed();
        importDisabled = !setupState.offlineImportAvailable();
        lastFailure = setupState.failure();
        statusMessage = setupState.statusMessage();
    }

    @Override public void removed() {
        disposeSetupResources();
        super.removed();
    }

    @Override public void onClose() {
        disposeSetupResources();
        super.onClose();
    }

    private void disposeSetupResources() {
        if (disposed) return;
        disposed = true;
        ImportJob current = job;
        if (current != null && current.running) current.lifecycle.dispose();
        importExecutor.shutdown();
    }

    private static final class ImportJob {
        final Path packagePath;
        final RuntimeSetupJobLifecycle lifecycle;
        volatile boolean running = true;
        volatile RuntimeDownloadProgress progress;
        ImportJob(Path packagePath, ExecutorService executor) {
            this.packagePath = packagePath;
            this.lifecycle = new RuntimeSetupJobLifecycle(executor);
        }
    }
}
