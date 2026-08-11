package dev.qingmo.mcwebui.bridge;

/** Explicit browser-to-host state subscription operation. */
public record BridgeSubscribe(int version, String channel) implements BridgeMessage {
    public BridgeSubscribe {
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        if (channel == null || channel.isBlank()) throw new IllegalArgumentException("channel must not be blank");
    }

    public BridgeSubscribe(String channel) { this(1, channel); }

    @Override public String type() { return "subscribe"; }
}
