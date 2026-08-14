package dev.qingmo.mcwebui.api.neoforge;

import java.util.Objects;

/** Immutable, backend-neutral snapshot suitable for diagnostics and integrations. */
public record MCWebUIBackendStatus(
        String preference,
        String resolvedBackend,
        String availability,
        String failureReason
) {
    public MCWebUIBackendStatus {
        preference = Objects.requireNonNull(preference, "preference");
        resolvedBackend = Objects.requireNonNull(resolvedBackend, "resolvedBackend");
        availability = Objects.requireNonNull(availability, "availability");
        failureReason = failureReason == null ? "" : failureReason;
    }

    public boolean available() { return "AVAILABLE".equals(availability); }
}
