package dev.qingmo.mcwebui.bridge;

/** Explicit browser-to-host state unsubscription operation. */
public record BridgeUnsubscribe(int version, String channel) implements BridgeMessage {
    public BridgeUnsubscribe {
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        if (channel == null || channel.isBlank()) throw new IllegalArgumentException("channel must not be blank");
    }

    public BridgeUnsubscribe(String channel) { this(1, channel); }

    @Override public String type() { return "unsubscribe"; }
}
