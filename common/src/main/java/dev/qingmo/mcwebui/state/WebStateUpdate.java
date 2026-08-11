package dev.qingmo.mcwebui.state;

public record WebStateUpdate(String channel, Object value, long revision) {
    public WebStateUpdate {
        if (channel == null || channel.isBlank()) throw new IllegalArgumentException("channel must not be blank");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
    }
}
