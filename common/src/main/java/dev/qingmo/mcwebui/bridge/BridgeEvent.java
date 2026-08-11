package dev.qingmo.mcwebui.bridge;

import java.util.Map;

public record BridgeEvent(int version, String channel, Map<String, Object> payload) implements BridgeMessage {
    public BridgeEvent {
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        if (channel == null || channel.isBlank()) throw new IllegalArgumentException("channel must not be blank");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public BridgeEvent(String channel, Map<String, Object> payload) { this(1, channel, payload); }

    @Override
    public String type() { return "event"; }
}
