package dev.qingmo.mcwebui.bridge;

import java.util.Set;

public record BridgeHandshake(int version, String runtime, Set<BridgeCapability> capabilities) implements BridgeMessage {
    public BridgeHandshake {
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        runtime = runtime == null ? "mcwebui" : runtime;
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    @Override
    public String type() { return "handshake"; }
}
