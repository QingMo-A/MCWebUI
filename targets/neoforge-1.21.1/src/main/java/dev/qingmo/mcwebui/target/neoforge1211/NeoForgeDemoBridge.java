package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.bridge.BridgeDispatcher;
import dev.qingmo.mcwebui.bridge.WebBridge;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

/** Explicit demo handlers used by the NeoForge client entrypoint; no reflection or command bridge. */
public final class NeoForgeDemoBridge {
    private final AtomicInteger counter = new AtomicInteger();
    private final CopyOnWriteArrayList<WebBridge> bridges = new CopyOnWriteArrayList<>();
    private final Clock clock;

    public NeoForgeDemoBridge() { this(Clock.systemUTC()); }
    public NeoForgeDemoBridge(Clock clock) { this.clock = java.util.Objects.requireNonNull(clock, "clock"); }

    public void register(BridgeDispatcher dispatcher) {
        dispatcher.register("demo.ping", request -> Map.of(
                "message", "pong",
                "timestamp", clock.millis(),
                "echo", request.payload().getOrDefault("message", "hello")));
        dispatcher.register("demo.counter.increment", request -> {
            Object raw = request.payload().getOrDefault("amount", 1);
            int amount = raw instanceof Number number ? number.intValue() : 1;
            int value = counter.addAndGet(Math.max(-100, Math.min(100, amount)));
            bridges.removeIf(WebBridge::isClosed);
            bridges.forEach(bridge -> {
                try { bridge.publishState("demo.counter", value); } catch (RuntimeException ignored) { }
            });
            return Map.of("value", value);
        });
    }

    public void publishCounter(WebBridge bridge) {
        bridges.addIfAbsent(bridge);
        bridge.publishState("demo.counter", counter.get());
    }

    public int counter() { return counter.get(); }
}
