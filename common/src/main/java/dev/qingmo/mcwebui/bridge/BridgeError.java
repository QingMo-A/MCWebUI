package dev.qingmo.mcwebui.bridge;

import java.util.Map;
import java.util.Objects;

public record BridgeError(String code, String message, Map<String, Object> details) {
    public BridgeError {
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static BridgeError malformed(String message) {
        return new BridgeError("MALFORMED_REQUEST", message, Map.of());
    }

    public static BridgeError unknownMethod(String method) {
        return new BridgeError("UNKNOWN_METHOD", "Unknown bridge method: " + method, Map.of("method", method));
    }

    public static BridgeError denied(String message) {
        return new BridgeError("CAPABILITY_DENIED", message, Map.of());
    }

    public static BridgeError internal(String message) {
        return new BridgeError("INTERNAL_ERROR", message, Map.of());
    }
}
