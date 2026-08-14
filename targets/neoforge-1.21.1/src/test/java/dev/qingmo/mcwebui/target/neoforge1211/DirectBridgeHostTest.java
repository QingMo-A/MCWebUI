package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.bridge.BridgeCapability;
import dev.qingmo.mcwebui.bridge.BridgeCodec;
import dev.qingmo.mcwebui.bridge.BridgeDispatcher;
import dev.qingmo.mcwebui.bridge.BridgeHandshake;
import dev.qingmo.mcwebui.bridge.BridgeRequest;
import dev.qingmo.mcwebui.bridge.BridgeResponse;
import dev.qingmo.mcwebui.bridge.BridgeSubscribe;
import dev.qingmo.mcwebui.bridge.WebBridge;
import dev.qingmo.mcwebui.security.WebOrigin;
import dev.qingmo.mcwebui.security.WebPermissionPolicy;
import dev.qingmo.mcwebui.state.WebStateStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DirectBridgeHostTest {
    private static WebBridge bridge(WebStateStore store) {
        BridgeDispatcher dispatcher = new BridgeDispatcher().register("demo.ping",
                request -> Map.of("echo", request.payload().get("message")));
        return new WebBridge(WebOrigin.mcui("playground.mcwebui"),
                new WebPermissionPolicy(EnumSet.of(BridgeCapability.HANDSHAKE,
                        BridgeCapability.RPC, BridgeCapability.STATE, BridgeCapability.EVENTS), false),
                dispatcher, store);
    }

    @Test void handshakeRpcAndStateShareTheCommonProtocol() {
        WebStateStore store = new WebStateStore();
        store.publish("demo.counter", 4);
        ArrayList<String> outbound = new ArrayList<>();
        try (WebBridge bridge = bridge(store);
             DirectBridgeHost host = new DirectBridgeHost(bridge, outbound::add)) {
            assertInstanceOf(BridgeHandshake.class,
                    BridgeCodec.decode(host.handle("{\"version\":1,\"type\":\"handshake\"}")));
            assertTrue(host.connected());

            BridgeResponse response = (BridgeResponse) BridgeCodec.decode(host.handle(BridgeCodec.encode(
                    new BridgeRequest("ping-1", "demo.ping", Map.of("message", "你好，Direct CEF")))));
            assertTrue(response.success());
            assertEquals("你好，Direct CEF", response.payload().get("echo"));

            assertEquals("{}", host.handle(BridgeCodec.encode(new BridgeSubscribe("demo.counter"))));
            assertFalse(outbound.isEmpty());
            assertEquals("state", BridgeCodec.decode(outbound.getFirst()).type());
        }
    }

    @Test void navigationResetRequiresASecondHandshake() {
        try (WebBridge bridge = bridge(new WebStateStore());
             DirectBridgeHost host = new DirectBridgeHost(bridge, ignored -> { })) {
            host.handle("{\"version\":1,\"type\":\"handshake\"}");
            host.resetSession();
            assertFalse(host.connected());
            BridgeResponse response = (BridgeResponse) BridgeCodec.decode(host.handle(BridgeCodec.encode(
                    new BridgeRequest("after-nav", "demo.ping", Map.of("message", "blocked")))));
            assertFalse(response.success());
            assertEquals("CAPABILITY_DENIED", response.error().code());
        }
    }

    @Test void stateSubscriptionIsDeniedBeforeHandshake() {
        try (WebBridge bridge = bridge(new WebStateStore());
             DirectBridgeHost host = new DirectBridgeHost(bridge, ignored -> { })) {
            BridgeResponse response = (BridgeResponse) BridgeCodec.decode(
                    host.handle(BridgeCodec.encode(new BridgeSubscribe("demo.counter"))));
            assertFalse(response.success());
            assertEquals("CAPABILITY_DENIED", response.error().code());
        }
    }
}
