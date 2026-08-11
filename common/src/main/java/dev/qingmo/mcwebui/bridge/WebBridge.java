package dev.qingmo.mcwebui.bridge;

import dev.qingmo.mcwebui.security.WebOrigin;
import dev.qingmo.mcwebui.security.WebPermissionPolicy;
import dev.qingmo.mcwebui.state.WebStateStore;
import dev.qingmo.mcwebui.state.WebStateSubscription;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** JSON-neutral bridge boundary. Targets provide transport and invoke these methods. */
public final class WebBridge implements AutoCloseable {
    private final WebOrigin origin;
    private final WebPermissionPolicy permissions;
    private final BridgeDispatcher dispatcher;
    private final WebStateStore stateStore;
    private final CopyOnWriteArrayList<Consumer<BridgeEvent>> eventSubscribers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<WebStateSubscription> stateSubscriptions = new CopyOnWriteArrayList<>();
    private volatile Set<BridgeCapability> grantedCapabilities = Set.of();
    private volatile boolean closed;

    public WebBridge(WebOrigin origin, WebPermissionPolicy permissions, BridgeDispatcher dispatcher, WebStateStore stateStore) {
        this.origin = Objects.requireNonNull(origin, "origin");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    public BridgeHandshake handshake() {
        ensureOpen();
        permissions.require(origin, BridgeCapability.HANDSHAKE);
        EnumSet<BridgeCapability> capabilities = EnumSet.noneOf(BridgeCapability.class);
        for (BridgeCapability capability : permissions.localCapabilities()) {
            if (permissions.allows(origin, capability)) capabilities.add(capability);
        }
        grantedCapabilities = Set.copyOf(capabilities);
        return new BridgeHandshake(1, "mcwebui", grantedCapabilities);
    }

    public BridgeResponse request(BridgeRequest request) {
        ensureOpen();
        if (grantedCapabilities.isEmpty()) {
            return BridgeResponse.failure(request == null || request.id() == null ? "invalid" : request.id(),
                    BridgeError.denied("Bridge handshake is required before requests"));
        }
        return dispatcher.dispatch(request, grantedCapabilities);
    }

    public WebStateSubscription subscribeState(String channel, Consumer<BridgeStateUpdate> subscriber) {
        ensureOpen();
        permissions.require(origin, BridgeCapability.STATE);
        Objects.requireNonNull(subscriber, "subscriber");
        Consumer<dev.qingmo.mcwebui.state.WebStateUpdate> adapter = update -> subscriber.accept(
                new BridgeStateUpdate(update.channel(), update.value(), update.revision()));
        WebStateSubscription storeSubscription = stateStore.subscribe(channel, adapter);
        stateSubscriptions.add(storeSubscription);
        return () -> {
            storeSubscription.close();
            stateSubscriptions.remove(storeSubscription);
        };
    }

    public void publishState(String channel, Object value) {
        ensureOpen();
        permissions.require(origin, BridgeCapability.STATE);
        stateStore.publish(channel, value);
    }

    public WebStateSubscription onEvent(Consumer<BridgeEvent> subscriber) {
        ensureOpen();
        permissions.require(origin, BridgeCapability.EVENTS);
        Objects.requireNonNull(subscriber, "subscriber");
        eventSubscribers.add(subscriber);
        return () -> eventSubscribers.remove(subscriber);
    }

    public void emit(String channel, Map<String, Object> payload) {
        ensureOpen();
        permissions.require(origin, BridgeCapability.EVENTS);
        BridgeEvent event = new BridgeEvent(channel, payload);
        for (Consumer<BridgeEvent> subscriber : eventSubscribers) {
            try { subscriber.accept(event); } catch (RuntimeException ignored) { }
        }
    }

    public WebOrigin origin() { return origin; }
    public WebStateStore stateStore() { return stateStore; }
    public boolean isClosed() { return closed; }

    /**
     * Forget the browser-side handshake when a page navigates away. The view remains usable for a
     * later trusted reload, but every new page must explicitly handshake again.
     */
    public void resetSession() {
        ensureOpen();
        grantedCapabilities = Set.of();
    }

    @Override
    public void close() {
        closed = true;
        eventSubscribers.clear();
        stateSubscriptions.forEach(WebStateSubscription::close);
        stateSubscriptions.clear();
        grantedCapabilities = Set.of();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Bridge is closed");
    }
}
