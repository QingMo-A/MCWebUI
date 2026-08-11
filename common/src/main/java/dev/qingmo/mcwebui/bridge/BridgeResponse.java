package dev.qingmo.mcwebui.bridge;

import java.util.Map;

public record BridgeResponse(int version, String id, boolean success, Map<String, Object> payload,
                             BridgeError error) implements BridgeMessage {
    public BridgeResponse {
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        if (success && error != null) throw new IllegalArgumentException("successful response cannot contain an error");
        if (!success && error == null) throw new IllegalArgumentException("failed response must contain an error");
    }

    public static BridgeResponse ok(String id, Map<String, Object> payload) {
        return new BridgeResponse(1, id, true, payload, null);
    }

    public static BridgeResponse failure(String id, BridgeError error) {
        return new BridgeResponse(1, id, false, Map.of(), error);
    }

    @Override
    public String type() { return "response"; }
}
