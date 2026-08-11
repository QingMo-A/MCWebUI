package dev.qingmo.mcwebui.bridge;

import java.util.Map;

public record BridgeStateUpdate(int version, String channel, Object value, long revision) implements BridgeMessage {
    public BridgeStateUpdate {
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        if (channel == null || channel.isBlank()) throw new IllegalArgumentException("channel must not be blank");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    }

    public BridgeStateUpdate(String channel, Object value, long revision) { this(1, channel, value, revision); }

    @Override
    public String type() { return "state"; }
}
