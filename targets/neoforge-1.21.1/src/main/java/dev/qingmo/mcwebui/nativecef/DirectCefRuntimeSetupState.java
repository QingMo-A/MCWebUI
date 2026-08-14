package dev.qingmo.mcwebui.nativecef;

import java.nio.file.Path;

/**
 * Pure-Java availability model shared by the bootstrap screen and tests.  It
 * keeps an explicit override authoritative: a failed override is a developer
 * error, even when a valid standard runtime exists.
 */
public final class DirectCefRuntimeSetupState {
    private final boolean overrideConfigured;
    private final boolean developerOverrideError;
    private final DirectCefRuntimeReleaseDescriptor releaseDescriptor;
    private volatile boolean installed;
    private volatile boolean importDisabled;
    private volatile DirectCefRuntimeException failure;
    private volatile String statusMessage;

    private DirectCefRuntimeSetupState(boolean overrideConfigured, DirectCefRuntimeDiscovery.Probe probe,
                                       DirectCefRuntimeReleaseDescriptor releaseDescriptor) {
        this.overrideConfigured = overrideConfigured;
        this.developerOverrideError = overrideConfigured && (probe == null || !probe.valid());
        this.releaseDescriptor = releaseDescriptor;
        applyProbe(probe);
    }

    public static DirectCefRuntimeSetupState initial(Path explicitOverride,
                                                      DirectCefRuntimeDiscovery.Probe probe,
                                                      DirectCefRuntimeReleaseDescriptor descriptor) {
        return new DirectCefRuntimeSetupState(explicitOverride != null, probe, descriptor);
    }

    public boolean overrideConfigured() { return overrideConfigured; }
    public boolean developerOverrideError() { return developerOverrideError; }
    public boolean installed() { return installed; }
    public DirectCefRuntimeException failure() { return failure; }
    public String statusMessage() { return statusMessage; }
    public boolean importAvailable(boolean packagePathPresent) {
        return !installed && !importDisabled && !developerOverrideError && packagePathPresent;
    }
    public boolean downloadAvailable() {
        return !installed && !importDisabled && !developerOverrideError
                && validDownloadDescriptor();
    }
    public boolean continueAvailable() { return installed; }

    private boolean validDownloadDescriptor() {
        if (releaseDescriptor == null || !releaseDescriptor.sourceConfigured()) return false;
        try {
            releaseDescriptor.validateFor(DirectCefRuntimeRequirement.required());
            return true;
        } catch (DirectCefRuntimeException ignored) {
            return false;
        }
    }
    public boolean offlineImportAvailable() { return !developerOverrideError && !importDisabled; }

    public synchronized void applyProbe(DirectCefRuntimeDiscovery.Probe probe) {
        if (probe != null && probe.valid()) {
            installed = true;
            importDisabled = false;
            failure = null;
            statusMessage = "Runtime installed successfully.";
            return;
        }
        installed = false;
        failure = probe == null ? null : probe.failure();
        importDisabled = developerOverrideError || (failure != null
                && !DirectCefRuntimeSetupMessages.canImport(failure.reason()));
        statusMessage = developerOverrideError ? "DEVELOPER OVERRIDE ERROR"
                : failure == null ? DirectCefRuntimeSetupMessages.messageFor(DirectCefRuntimeFailureReason.NOT_FOUND)
                : DirectCefRuntimeSetupMessages.messageFor(failure.reason());
    }

    public synchronized void markInstalled() {
        installed = true;
        importDisabled = false;
        failure = null;
        statusMessage = "Runtime installed successfully.";
    }

    public synchronized void markFailure(DirectCefRuntimeException error) {
        installed = false;
        failure = error;
        importDisabled = developerOverrideError || (error != null
                && !DirectCefRuntimeSetupMessages.canImport(error.reason()));
        statusMessage = developerOverrideError ? "DEVELOPER OVERRIDE ERROR"
                : error == null ? DirectCefRuntimeSetupMessages.messageFor(DirectCefRuntimeFailureReason.IO_ERROR)
                : DirectCefRuntimeSetupMessages.messageFor(error.reason());
    }
}
