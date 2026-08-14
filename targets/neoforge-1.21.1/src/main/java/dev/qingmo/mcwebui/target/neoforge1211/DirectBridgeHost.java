package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.bridge.BridgeCodec;
import dev.qingmo.mcwebui.bridge.BridgeError;
import dev.qingmo.mcwebui.bridge.BridgeHandshake;
import dev.qingmo.mcwebui.bridge.BridgeMessage;
import dev.qingmo.mcwebui.bridge.BridgeRequest;
import dev.qingmo.mcwebui.bridge.BridgeResponse;
import dev.qingmo.mcwebui.bridge.BridgeSubscribe;
import dev.qingmo.mcwebui.bridge.BridgeUnsubscribe;
import dev.qingmo.mcwebui.bridge.WebBridge;
import dev.qingmo.mcwebui.state.WebStateSubscription;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/** Protocol host for Direct CEF. Native code transports JSON; common Java owns all bridge semantics. */
final class DirectBridgeHost implements AutoCloseable {
    private final WebBridge bridge;
    private final Consumer<String> outbound;
    private final ConcurrentMap<String, WebStateSubscription> subscriptions = new ConcurrentHashMap<>();
    private final WebStateSubscription eventSubscription;
    private volatile boolean closed;
    private volatile boolean connected;

    DirectBridgeHost(WebBridge bridge, Consumer<String> outbound) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.eventSubscription = bridge.onEvent(event -> send(BridgeCodec.encode(event)));
    }

    String handle(String encoded) {
        if (closed) return BridgeCodec.encode(BridgeResponse.failure("invalid",
                BridgeError.denied("Bridge view is closed")));
        final BridgeMessage message;
        try {
            message = BridgeCodec.decode(encoded);
        } catch (RuntimeException ex) {
            return BridgeCodec.encode(BridgeResponse.failure("invalid",
                    BridgeError.malformed("Invalid bridge envelope")));
        }
        try {
            if (message instanceof BridgeHandshake) {
                String response = BridgeCodec.encode(bridge.handshake());
                connected = true;
                return response;
            }
            if (message instanceof BridgeRequest request) return BridgeCodec.encode(bridge.request(request));
            if (message instanceof BridgeSubscribe subscribe) {
                subscribe(subscribe.channel());
                return "{}";
            }
            if (message instanceof BridgeUnsubscribe unsubscribe) {
                unsubscribe(unsubscribe.channel());
                return "{}";
            }
            return BridgeCodec.encode(BridgeResponse.failure("invalid",
                    BridgeError.malformed("Unsupported browser-to-host message")));
        } catch (SecurityException ex) {
            return BridgeCodec.encode(BridgeResponse.failure("invalid", BridgeError.denied(ex.getMessage())));
        } catch (RuntimeException ex) {
            return BridgeCodec.encode(BridgeResponse.failure("invalid",
                    BridgeError.internal("Bridge transport failed")));
        }
    }

    void resetSession() {
        if (closed) return;
        connected = false;
        subscriptions.values().forEach(WebStateSubscription::close);
        subscriptions.clear();
        bridge.resetSession();
    }

    boolean connected() { return connected && !closed; }

    private void subscribe(String channel) {
        subscriptions.computeIfAbsent(channel,
                key -> bridge.subscribeState(key, update -> send(BridgeCodec.encode(update))));
    }

    private void unsubscribe(String channel) {
        WebStateSubscription subscription = subscriptions.remove(channel);
        if (subscription != null) subscription.close();
    }

    private void send(String encoded) {
        // Events/state belong to the currently negotiated browser document.
        // Navigation clears connected before subscriptions are closed, so an
        // in-flight callback cannot leak an old document's message into the
        // newly loaded page.
        if (!closed && connected) outbound.accept(encoded);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        connected = false;
        subscriptions.values().forEach(WebStateSubscription::close);
        subscriptions.clear();
        eventSubscription.close();
    }
}
