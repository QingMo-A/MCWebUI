package dev.qingmo.mcwebui.bridge;

import java.util.Map;
import java.util.Objects;

public record BridgeRequest(int version, String id, String method, Map<String, Object> payload) implements BridgeMessage {
    public BridgeRequest {
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (method == null || method.isBlank()) throw new IllegalArgumentException("method must not be blank");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public BridgeRequest(String id, String method, Map<String, Object> payload) {
        this(1, id, method, payload);
    }

    @Override
    public String type() { return "request"; }
}
