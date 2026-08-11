package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.backend.BrowserBackend;
import dev.qingmo.mcwebui.bridge.BridgeCapability;
import dev.qingmo.mcwebui.bridge.BridgeDispatcher;
import dev.qingmo.mcwebui.runtime.DefaultWebRuntime;
import dev.qingmo.mcwebui.runtime.WebRuntime;
import dev.qingmo.mcwebui.security.WebPermissionPolicy;

import java.util.EnumSet;

/** Client-only NeoForge adapter factory. Loader event wiring remains target-owned. */
public final class NeoForgeRuntimeAdapter implements AutoCloseable {
    private final WebRuntime runtime;
    private final BrowserBackend backend;
    private final NeoForgeDemoBridge demoBridge;

    public NeoForgeRuntimeAdapter(BrowserBackend backend, BridgeDispatcher dispatcher) {
        this(backend, dispatcher, new NeoForgeDemoBridge());
    }

    public NeoForgeRuntimeAdapter(BrowserBackend backend, BridgeDispatcher dispatcher, NeoForgeDemoBridge demoBridge) {
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        this.demoBridge = java.util.Objects.requireNonNull(demoBridge, "demoBridge");
        this.demoBridge.register(dispatcher);
        this.runtime = new DefaultWebRuntime(new WebPermissionPolicy(
                EnumSet.of(BridgeCapability.HANDSHAKE, BridgeCapability.RPC, BridgeCapability.EVENTS,
                BridgeCapability.STATE, BridgeCapability.INPUT), false), dispatcher);
    }

    public WebRuntime runtime() { return runtime; }
    public BrowserBackend backend() { return backend; }
    public NeoForgeDemoBridge demoBridge() { return demoBridge; }

    @Override
    public void close() {
        runtime.close();
        backend.close();
    }
}
