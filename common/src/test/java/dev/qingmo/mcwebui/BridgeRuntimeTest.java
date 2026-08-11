package dev.qingmo.mcwebui;

import dev.qingmo.mcwebui.bridge.BridgeCapability;
import dev.qingmo.mcwebui.bridge.BridgeCodec;
import dev.qingmo.mcwebui.bridge.BridgeDispatcher;
import dev.qingmo.mcwebui.bridge.BridgeRequest;
import dev.qingmo.mcwebui.bridge.BridgeResponse;
import dev.qingmo.mcwebui.bridge.BridgeStateUpdate;
import dev.qingmo.mcwebui.bridge.BridgeSubscribe;
import dev.qingmo.mcwebui.bridge.BridgeUnsubscribe;
import dev.qingmo.mcwebui.bridge.WebBridge;
import dev.qingmo.mcwebui.runtime.DefaultWebRuntime;
import dev.qingmo.mcwebui.runtime.WebView;
import dev.qingmo.mcwebui.runtime.WebViewConfig;
import dev.qingmo.mcwebui.runtime.WebViewLifecycle;
import dev.qingmo.mcwebui.security.WebOrigin;
import dev.qingmo.mcwebui.security.WebPermissionPolicy;
import dev.qingmo.mcwebui.state.WebStateStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BridgeRuntimeTest {
    @Test
    void dispatchPreservesRequestIdAndStructuredPayload() {
        BridgeDispatcher dispatcher = new BridgeDispatcher()
                .register("demo.ping", request -> Map.of("message", "pong"));
        BridgeResponse response = dispatcher.dispatch(new BridgeRequest("req-7", "demo.ping", Map.of()),
                EnumSet.of(BridgeCapability.RPC));
        assertTrue(response.success());
        assertEquals("req-7", response.id());
        assertEquals("pong", response.payload().get("message"));
    }

    @Test
    void bridgeCodecRoundTripsEnvelope() {
        var original = new BridgeRequest("codec-1", "demo.ping", Map.of("message", "你好"));
        var decoded = BridgeCodec.decode(BridgeCodec.encode(original));
        assertInstanceOf(BridgeRequest.class, decoded);
        assertEquals(original, decoded);
    }

    @Test
    void explicitStateSubscriptionOperationsRoundTrip() {
        assertEquals(new BridgeSubscribe("demo.counter"), BridgeCodec.decode(BridgeCodec.encode(new BridgeSubscribe("demo.counter"))));
        assertEquals(new BridgeUnsubscribe("demo.counter"), BridgeCodec.decode(BridgeCodec.encode(new BridgeUnsubscribe("demo.counter"))));
        var state = (BridgeStateUpdate) BridgeCodec.decode(BridgeCodec.encode(new BridgeStateUpdate("demo.counter", 4, 2)));
        assertEquals("demo.counter", state.channel());
        assertEquals(4.0, ((Number) state.value()).doubleValue());
        assertEquals(2, state.revision());
    }

    @Test
    void unknownMalformedAndThrowingHandlersBecomeStructuredErrors() {
        BridgeDispatcher dispatcher = new BridgeDispatcher()
                .register("demo.fail", request -> { throw new IllegalStateException("secret"); });
        BridgeResponse unknown = dispatcher.dispatch(new BridgeRequest("x", "missing", Map.of()), EnumSet.of(BridgeCapability.RPC));
        assertFalse(unknown.success());
        assertEquals("UNKNOWN_METHOD", unknown.error().code());
        BridgeResponse failed = dispatcher.dispatch(new BridgeRequest("y", "demo.fail", Map.of()), EnumSet.of(BridgeCapability.RPC));
        assertEquals("INTERNAL_ERROR", failed.error().code());
        BridgeResponse malformed = dispatcher.dispatch(null, EnumSet.of(BridgeCapability.RPC));
        assertEquals("MALFORMED_REQUEST", malformed.error().code());
    }

    @Test
    void capabilityAndOriginAreEnforced() {
        WebPermissionPolicy policy = new WebPermissionPolicy(EnumSet.of(BridgeCapability.HANDSHAKE, BridgeCapability.RPC), false);
        BridgeDispatcher dispatcher = new BridgeDispatcher().register("demo.ping", request -> Map.of("ok", true));
        WebBridge bridge = new WebBridge(WebOrigin.mcui("playground"), policy, dispatcher, new WebStateStore());
        assertEquals("mcwebui", bridge.handshake().runtime());
        assertTrue(bridge.request(new BridgeRequest("1", "demo.ping", Map.of())).success());
        assertThrows(SecurityException.class, () -> new WebBridge(WebOrigin.parse("https://example.com"), policy, dispatcher, new WebStateStore()).handshake());
    }

    @Test
    void stateHasLatestValueAndUnsubscribeSemantics() {
        WebStateStore store = new WebStateStore();
        store.publish("demo.counter", 3);
        ArrayList<Object> seen = new ArrayList<>();
        var subscription = store.subscribe("demo.counter", update -> seen.add(update.value()));
        store.publish("demo.counter", 4);
        subscription.close();
        store.publish("demo.counter", 5);
        assertEquals(java.util.List.of(3, 4), seen);
        assertEquals(0, store.subscriberCount("demo.counter"));
    }

    @Test
    void bridgeSessionResetRequiresHandshakeAgain() {
        WebPermissionPolicy policy = new WebPermissionPolicy(EnumSet.of(BridgeCapability.HANDSHAKE, BridgeCapability.RPC), false);
        BridgeDispatcher dispatcher = new BridgeDispatcher().register("demo.ping", request -> Map.of("ok", true));
        WebBridge bridge = new WebBridge(WebOrigin.mcui("playground"), policy, dispatcher, new WebStateStore());
        assertTrue(bridge.request(new BridgeRequest("before", "demo.ping", Map.of())).error() != null);
        bridge.handshake();
        assertTrue(bridge.request(new BridgeRequest("ok", "demo.ping", Map.of())).success());
        bridge.resetSession();
        assertEquals("CAPABILITY_DENIED", bridge.request(new BridgeRequest("after", "demo.ping", Map.of())).error().code());
    }

    @Test
    void viewLifecycleClosesBridgeAndRemovesView() {
        DefaultWebRuntime runtime = new DefaultWebRuntime();
        WebView view = runtime.createView(new WebViewConfig(WebOrigin.mcui("playground"), "/index.html", 320, 200));
        assertEquals(WebViewLifecycle.CREATED, view.state().lifecycle());
        view.initialize();
        view.setVisible(true);
        assertEquals(WebViewLifecycle.VISIBLE, view.state().lifecycle());
        view.close(); view.close();
        assertEquals(WebViewLifecycle.DISPOSED, view.state().lifecycle());
        assertTrue(view.bridge().isClosed());
        assertEquals(0, runtime.liveViewCount());
    }
}
