package dev.qingmo.mcwebui.bridge;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Registry-based RPC dispatcher. Handlers are explicitly registered with a capability. */
public final class BridgeDispatcher {
    private record Registration(BridgeCapability capability, BridgeHandler handler) {}

    private final Map<String, Registration> handlers = new ConcurrentHashMap<>();

    public BridgeDispatcher register(String method, BridgeHandler handler) {
        return register(method, BridgeCapability.RPC, handler);
    }

    public BridgeDispatcher register(String method, BridgeCapability capability, BridgeHandler handler) {
        if (method == null || !method.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")) {
            throw new IllegalArgumentException("Invalid bridge method: " + method);
        }
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(handler, "handler");
        if (handlers.putIfAbsent(method, new Registration(capability, handler)) != null) {
            throw new IllegalArgumentException("Bridge method already registered: " + method);
        }
        return this;
    }

    public boolean contains(String method) {
        return handlers.containsKey(method);
    }

    public BridgeResponse dispatch(BridgeRequest request, java.util.Set<BridgeCapability> grantedCapabilities) {
        if (request == null || request.id() == null || request.id().isBlank()
                || request.method() == null || request.method().isBlank()) {
            return BridgeResponse.failure(request == null || request.id() == null ? "invalid" : request.id(),
                    BridgeError.malformed("Request must include a non-empty id and method"));
        }
        Registration registration = handlers.get(request.method());
        if (registration == null) {
            return BridgeResponse.failure(request.id(), BridgeError.unknownMethod(request.method()));
        }
        if (grantedCapabilities == null || !grantedCapabilities.contains(registration.capability())) {
            return BridgeResponse.failure(request.id(), BridgeError.denied("Capability required: "
                    + registration.capability().name().toLowerCase()));
        }
        try {
            Object result = registration.handler().handle(request);
            Map<String, Object> payload;
            if (result instanceof Map<?, ?> map) {
                payload = copyMap(map);
            } else {
                java.util.LinkedHashMap<String, Object> single = new java.util.LinkedHashMap<>();
                single.put("result", result);
                payload = java.util.Collections.unmodifiableMap(single);
            }
            return BridgeResponse.ok(request.id(), payload);
        } catch (SecurityException ex) {
            return BridgeResponse.failure(request.id(), BridgeError.denied(ex.getMessage() == null ? "Denied" : ex.getMessage()));
        } catch (Exception ex) {
            // Do not expose implementation stack traces to a web page.
            return BridgeResponse.failure(request.id(), BridgeError.internal("Bridge handler failed"));
        }
    }

    private static Map<String, Object> copyMap(Map<?, ?> value) {
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (!(key instanceof String stringKey)) {
                throw new IllegalArgumentException("Bridge payload map keys must be strings");
            }
            copy.put(stringKey, item);
        });
        return Map.copyOf(copy);
    }
}
