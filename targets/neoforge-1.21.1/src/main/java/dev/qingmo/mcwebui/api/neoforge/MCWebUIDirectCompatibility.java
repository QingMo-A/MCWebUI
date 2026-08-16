package dev.qingmo.mcwebui.api.neoforge;

import java.util.Objects;

/** Immutable compatibility diagnostics without native handles or graphics objects. */
public record MCWebUIDirectCompatibility(
        String staticStatus,
        String graphicsStatus,
        String architecture,
        String requiredGraphicsInterop,
        String glVendor,
        String glRenderer,
        String glVersion,
        String failureReason
) {
    public MCWebUIDirectCompatibility {
        staticStatus = Objects.requireNonNull(staticStatus, "staticStatus");
        graphicsStatus = Objects.requireNonNull(graphicsStatus, "graphicsStatus");
        architecture = Objects.requireNonNull(architecture, "architecture");
        requiredGraphicsInterop = Objects.requireNonNull(requiredGraphicsInterop, "requiredGraphicsInterop");
        glVendor = glVendor == null ? "" : glVendor;
        glRenderer = glRenderer == null ? "" : glRenderer;
        glVersion = glVersion == null ? "" : glVersion;
        failureReason = failureReason == null ? "" : failureReason;
    }

    public boolean supported() { return "ELIGIBLE".equals(staticStatus) && "SUPPORTED".equals(graphicsStatus); }
}
