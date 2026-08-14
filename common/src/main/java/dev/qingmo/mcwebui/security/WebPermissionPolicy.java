package dev.qingmo.mcwebui.security;

import dev.qingmo.mcwebui.bridge.BridgeCapability;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Capability gate shared by all bridge implementations.
 *
 * <p>{@code allowExternalNetwork} is application intent metadata for a backend
 * that implements request interception. It is not, by itself, a Chromium
 * network sandbox or firewall. Current bundled views reject external-network
 * mode in {@code WebViewConfig}.</p>
 */
public final class WebPermissionPolicy {
    private final Set<BridgeCapability> localCapabilities;
    private final boolean allowExternalNetwork;

    public WebPermissionPolicy() {
        this(EnumSet.of(BridgeCapability.HANDSHAKE, BridgeCapability.RPC,
                BridgeCapability.EVENTS, BridgeCapability.STATE, BridgeCapability.INPUT), false);
    }

    public WebPermissionPolicy(Set<BridgeCapability> localCapabilities, boolean allowExternalNetwork) {
        Objects.requireNonNull(localCapabilities, "localCapabilities");
        this.localCapabilities = localCapabilities.isEmpty()
                ? Set.of()
                : EnumSet.copyOf(localCapabilities);
        this.allowExternalNetwork = allowExternalNetwork;
    }

    public boolean isTrusted(WebOrigin origin) {
        return origin != null && origin.isTrustedLocal();
    }

    public boolean allows(WebOrigin origin, BridgeCapability capability) {
        Objects.requireNonNull(capability, "capability");
        if (origin == null || !isTrusted(origin)) {
            return false;
        }
        return localCapabilities.contains(capability);
    }

    public void require(WebOrigin origin, BridgeCapability capability) {
        if (!allows(origin, capability)) {
            throw new SecurityException("Bridge capability denied for " + (origin == null ? "<unknown>" : origin.asUri())
                    + ": " + capability.name().toLowerCase());
        }
    }

    public boolean allowsExternalNetwork(WebOrigin origin) {
        return allowExternalNetwork && isTrusted(origin);
    }

    public Set<BridgeCapability> localCapabilities() {
        return Set.copyOf(localCapabilities);
    }
}
