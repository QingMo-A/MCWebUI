package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.bridge.BridgeDispatcher;
import dev.qingmo.mcwebui.bridge.WebBridge;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/** Explicit demo handlers used by the NeoForge client entrypoint; no reflection or command bridge. */
public final class NeoForgeDemoBridge {
    private final AtomicInteger counter = new AtomicInteger();
    private final CopyOnWriteArrayList<WebBridge> bridges = new CopyOnWriteArrayList<>();
    private final AtomicReference<Supplier<Map<String, Object>>> diagnostics = new AtomicReference<>(Map::of);
    private final Clock clock;

    public NeoForgeDemoBridge() { this(Clock.systemUTC()); }
    public NeoForgeDemoBridge(Clock clock) { this.clock = java.util.Objects.requireNonNull(clock, "clock"); }

    public void register(BridgeDispatcher dispatcher) {
        dispatcher.register("demo.ping", request -> Map.of(
                "message", "pong",
                "timestamp", clock.millis(),
                "echo", request.payload().getOrDefault("message", "hello")));
        dispatcher.register("runtime.diagnostics", request -> diagnostics.get().get());
        dispatcher.register("demo.error", request -> { throw new IllegalArgumentException("safe demo failure"); });
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

    public void setDiagnosticsSupplier(Supplier<Map<String, Object>> supplier) {
        diagnostics.set(java.util.Objects.requireNonNull(supplier, "supplier"));
    }

    public void publishCounter(WebBridge bridge) {
        bridges.addIfAbsent(bridge);
        bridge.publishState("demo.counter", counter.get());
    }

    public void removeBridge(WebBridge bridge) {
        if (bridge != null) bridges.remove(bridge);
    }

    public int counter() { return counter.get(); }
}
