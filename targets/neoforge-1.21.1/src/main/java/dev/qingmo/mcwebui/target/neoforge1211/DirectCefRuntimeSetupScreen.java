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
    private Button retryButton;
    private Button openFolderButton;
    private Button cancelButton;
    private Button continueButton;
    private ImportJob job;
    private volatile RuntimeImportProgress progress;
    private volatile boolean installed;
    private volatile boolean importDisabled;
    private String statusMessage;
    private final boolean overrideConfigured;

    public DirectCefRuntimeSetupScreen(Path instanceRoot, DirectCefRuntimeDiscovery.Probe probe,
                                       Screen previousScreen, Runnable onContinue) {
        super(Component.literal("MCWebUI Browser Runtime Required"));
        this.instanceRoot = Objects.requireNonNull(instanceRoot, "instanceRoot").toAbsolutePath().normalize();
        this.requirement = DirectCefRuntimeRequirement.required();
        this.expectedDirectory = DirectCefRuntimeDiscovery.standardDirectory(this.instanceRoot, requirement);
        this.previousScreen = previousScreen;
        this.onContinue = Objects.requireNonNull(onContinue, "onContinue");
        this.overrideConfigured = configuredOverride() != null;
        String prefilled = System.getProperty("mcwebui.directCef.package", "").trim();
        if (!prefilled.isEmpty()) rememberedPackagePath = prefilled;
        if (probe != null && probe.valid()) {
            installed = true;
            statusMessage = "Runtime is installed.";
        } else {
            this.lastFailure = probe == null ? null : probe.failure();
            this.importDisabled = lastFailure != null
                    && !DirectCefRuntimeSetupMessages.canImport(lastFailure.reason());
            this.statusMessage = lastFailure == null
                    ? DirectCefRuntimeSetupMessages.messageFor(DirectCefRuntimeFailureReason.NOT_FOUND)
                    : DirectCefRuntimeSetupMessages.messageFor(lastFailure.reason());
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        packagePathBox = new EditBox(font, (width - 340) / 2, 108, 340, 20,
                Component.literal("Runtime package path"));
        packagePathBox.setMaxLength(4096);
        packagePathBox.setValue(rememberedPackagePath);
        packagePathBox.setResponder(value -> updateButtons());
        addRenderableWidget(packagePathBox);
        int rowY = 140;
        importButton = addRenderableWidget(Button.builder(Component.literal("Import Package"), button -> startImport())
                .bounds(buttonX(0), rowY, 110, 20).build());
        retryButton = addRenderableWidget(Button.builder(Component.literal("Retry"), button -> retry())
                .bounds(buttonX(1), rowY, 70, 20).build());
        openFolderButton = addRenderableWidget(Button.builder(Component.literal("Open Runtime Folder"), button -> openRuntimeFolder())
                .bounds(buttonX(2), rowY, 120, 20).build());
        cancelButton = addRenderableWidget(Button.builder(Component.literal("Close"), button -> cancel())
                .bounds(buttonX(3), rowY, 70, 20).build());
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
        graphics.drawCenteredString(font, Component.literal("Expected directory: "
                        + truncate(expectedDirectory.toString())),
                width / 2, 66, COLOR_GRAY);
        if (overrideConfigured) {
            graphics.drawCenteredString(font, Component.literal("Note: an explicit runtime override is configured;"
                            + " imported packages install into the standard directory"),
                    width / 2, 80, COLOR_RED);
        }
        if (lastFailure != null) {
            graphics.drawCenteredString(font, Component.literal("Reason code: " + lastFailure.reason().name()),
                    width / 2, 94, COLOR_GRAY);
        }
        graphics.drawCenteredString(font, Component.literal("Runtime package path (ZIP):"),
                width / 2, 98, COLOR_GRAY);
        renderProgress(graphics);
    }

    private void renderProgress(GuiGraphics graphics) {
        ImportJob current = job;
        RuntimeImportProgress snapshot = progress;
        if (current == null || snapshot == null) return;
        if (current.running) {
            graphics.drawCenteredString(font, Component.literal(phaseLabel(snapshot.phase())),
                    width / 2, 172, COLOR_WHITE);
            if (snapshot.currentFile() != null) {
                graphics.drawCenteredString(font, Component.literal(snapshot.currentFile()
                                + " (" + snapshot.filesCompleted() + "/" + snapshot.filesTotal() + ")"),
                        width / 2, 184, COLOR_GRAY);
                graphics.drawCenteredString(font, Component.literal(megabytes(snapshot.bytesExtracted())
                                + " / " + megabytes(snapshot.bytesExpected())),
                        width / 2, 196, COLOR_GRAY);
            }
        } else if (snapshot.phase() == RuntimeImportProgress.Phase.FAILED
                || snapshot.phase() == RuntimeImportProgress.Phase.CANCELLED) {
            graphics.drawCenteredString(font, Component.literal(phaseLabel(snapshot.phase())),
                    width / 2, 172, COLOR_RED);
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
        ImportJob newJob = new ImportJob(packagePath);
        job = newJob;
        progress = null;
        statusMessage = "Importing runtime package...";
        updateButtons();
        importExecutor.execute(() -> {
            RuntimeImportResult result = DirectCefRuntimePackageImporter.importPackage(packagePath, instanceRoot,
                    new DirectCefRuntimePackageImporter.Options(requirement, null,
                            p -> newJob.progress = p, newJob.token,
                            DirectCefRuntimePackageImporter.DEFAULT_LOCK_TIMEOUT_MILLIS));
            Minecraft.getInstance().execute(() -> onImportFinished(newJob, result));
        });
    }

    private void onImportFinished(ImportJob finished, RuntimeImportResult result) {
        finished.running = false;
        progress = finished.progress;
        switch (result.status()) {
            case INSTALLED, ALREADY_INSTALLED -> {
                DirectCefRuntimeDiscovery.Probe probe = DirectCefRuntimeDiscovery.probe(
                        instanceRoot, configuredOverride(), requirement);
                if (probe.valid()) {
                    installed = true;
                    importDisabled = false;
                    statusMessage = "Runtime installed successfully.";
                    LOGGER.info("MCWebUI Direct CEF runtime installed at {}", result.finalDirectory());
                } else {
                    lastFailure = probe.failure();
                    statusMessage = DirectCefRuntimeSetupMessages.messageFor(probe.failure().reason());
                }
            }
            case CANCELLED -> statusMessage = "Import cancelled.";
            case INSTALL_IN_PROGRESS -> statusMessage =
                    "Another process is already installing this runtime. Wait and retry.";
            case FAILED -> {
                lastFailure = result.failure();
                statusMessage = DirectCefRuntimeSetupMessages.messageFor(result.failure().reason());
                importDisabled = !DirectCefRuntimeSetupMessages.canImport(result.failure().reason());
                LOGGER.error("MCWebUI Direct CEF runtime package import failed: {}",
                        result.failure().getMessage(), result.failure());
            }
        }
        updateButtons();
    }

    private void retry() {
        DirectCefRuntimeDiscovery.Probe probe = DirectCefRuntimeDiscovery.probe(
                instanceRoot, configuredOverride(), requirement);
        if (probe.valid()) {
            installed = true;
            importDisabled = false;
            statusMessage = "Runtime installed successfully.";
        } else {
            lastFailure = probe.failure();
            statusMessage = DirectCefRuntimeSetupMessages.messageFor(probe.failure().reason());
            importDisabled = !DirectCefRuntimeSetupMessages.canImport(probe.failure().reason());
        }
        updateButtons();
    }

    private void continueToBrowser() {
        onContinue.run();
    }

    private void cancel() {
        ImportJob current = job;
        if (current != null && current.running) {
            current.token.cancel();
            cancelButton.active = false;
            statusMessage = "Cancelling...";
            return;
        }
        minecraft.setScreen(previousScreen);
    }

    private void openRuntimeFolder() {
        try {
            Path cefRoot = instanceRoot.resolve("mcwebui").resolve("runtime").resolve("cef");
            Files.createDirectories(cefRoot);
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
        importButton.active = !running && !installed && !importDisabled
                && !packagePathBox.getValue().isBlank();
        retryButton.active = !running;
        openFolderButton.active = !running;
        cancelButton.active = true;
        cancelButton.setMessage(Component.literal(running ? "Cancel" : "Close"));
        importButton.visible = !installed;
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
        int totalWidth = 110 + 70 + 120 + 70 + 3 * 8;
        int start = (width - totalWidth) / 2;
        return start + switch (index) {
            case 0 -> 0;
            case 1 -> 110 + 8;
            case 2 -> 110 + 8 + 70 + 8;
            default -> 110 + 8 + 70 + 8 + 120 + 8;
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

    private static String phaseLabel(RuntimeImportProgress.Phase phase) {
        return switch (phase) {
            case VALIDATING_PACKAGE -> "Validating package...";
            case WAITING_FOR_LOCK -> "Waiting for another installer...";
            case EXTRACTING -> "Extracting files...";
            case VALIDATING_RUNTIME -> "Validating installed files...";
            case PUBLISHING -> "Publishing runtime...";
            case COMPLETE -> "Complete.";
            case FAILED -> "Import failed.";
            case CANCELLED -> "Import cancelled.";
        };
    }

    private static Path configuredOverride() {
        String value = System.getProperty(DirectCefRuntimeDiscovery.RUNTIME_OVERRIDE_PROPERTY, "").trim();
        return value.isEmpty() ? null : Path.of(value);
    }

    @Override public void onClose() {
        ImportJob current = job;
        if (current != null && current.running) current.token.cancel();
        importExecutor.shutdown();
        super.onClose();
    }

    private static final class ImportJob {
        final Path packagePath;
        final CancellationToken token = new CancellationToken();
        volatile boolean running = true;
        volatile RuntimeImportProgress progress;
        ImportJob(Path packagePath) { this.packagePath = packagePath; }
    }

    private static final class CancellationToken implements DirectCefRuntimePackageImporter.CancellationToken {
        private volatile boolean cancelled;
        void cancel() { cancelled = true; }
        @Override public boolean isCancelled() { return cancelled; }
    }
}
