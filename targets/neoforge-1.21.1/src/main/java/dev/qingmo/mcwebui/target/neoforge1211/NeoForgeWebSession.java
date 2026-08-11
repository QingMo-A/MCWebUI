package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.backend.BrowserBackend;
import dev.qingmo.mcwebui.bridge.BridgeCapability;
import dev.qingmo.mcwebui.bridge.BridgeDispatcher;
import dev.qingmo.mcwebui.input.WebFocusEvent;
import dev.qingmo.mcwebui.input.WebKeyEvent;
import dev.qingmo.mcwebui.input.WebMouseEvent;
import dev.qingmo.mcwebui.input.WebScrollEvent;
import dev.qingmo.mcwebui.input.WebTextInputEvent;
import dev.qingmo.mcwebui.runtime.DefaultWebRuntime;
import dev.qingmo.mcwebui.runtime.WebRuntime;
import dev.qingmo.mcwebui.runtime.WebView;
import dev.qingmo.mcwebui.runtime.WebViewConfig;
import dev.qingmo.mcwebui.security.WebOrigin;
import dev.qingmo.mcwebui.security.WebPermissionPolicy;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Single NeoForge screen-owned composition of common view, MCEF surface and bridge session. */
final class NeoForgeWebSession implements AutoCloseable {
    private final WebRuntime runtime;
    private final BrowserBackend backend;
    private final NeoForgeDemoBridge demo;
    private WebView view;
    private NeoForgeRenderableSurface surface;
    private double guiScale = 1.0;
    private int minecraftGuiWidth;
    private int minecraftGuiHeight;
    private boolean initialized;
    private volatile boolean closed;

    NeoForgeWebSession(BridgeDispatcher dispatcher, NeoForgeDemoBridge demo) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        this.demo = Objects.requireNonNull(demo, "demo");
        this.runtime = new DefaultWebRuntime(new WebPermissionPolicy(EnumSet.of(
                BridgeCapability.HANDSHAKE, BridgeCapability.RPC, BridgeCapability.EVENTS,
                BridgeCapability.STATE, BridgeCapability.INPUT, BridgeCapability.CLIPBOARD), false), dispatcher);
        this.backend = new NeoForgeMcefBackend();
    }

    void init(int guiWidth, int guiHeight, double guiScale) {
        if (closed) throw new IllegalStateException("Session is closed");
        if (initialized) {
            resize(guiWidth, guiHeight, guiScale);
            return;
        }
        this.guiScale = requireScale(guiScale);
        this.minecraftGuiWidth = requireDimension(guiWidth, "guiWidth");
        this.minecraftGuiHeight = requireDimension(guiHeight, "guiHeight");
        int width = physical(guiWidth, guiScale);
        int height = physical(guiHeight, guiScale);
        try {
            view = runtime.createView(new WebViewConfig(WebOrigin.mcui("playground"), "/index.html", width, height));
            view.initialize();
            surface = (NeoForgeRenderableSurface) backend.createSurface(view.config(), view.bridge());
            view.setVisible(true);
            view.focus(true);
            surface.resize(width, height);
            surface.load("mcui://playground/index.html");
            demo.setDiagnosticsSupplier(this::diagnostics);
            demo.publishCounter(view.bridge());
            initialized = true;
        } catch (RuntimeException ex) {
            close();
            throw ex;
        }
    }

    void resize(int guiWidth, int guiHeight, double guiScale) {
        if (closed || view == null || surface == null) return;
        this.guiScale = requireScale(guiScale);
        this.minecraftGuiWidth = requireDimension(guiWidth, "guiWidth");
        this.minecraftGuiHeight = requireDimension(guiHeight, "guiHeight");
        int width = physical(guiWidth, guiScale);
        int height = physical(guiHeight, guiScale);
        view.resize(width, height);
        surface.resize(width, height);
    }

    void mouseMove(double x, double y) { input(new WebMouseEvent(WebMouseEvent.Type.MOVE, physical(x), physical(y), -1)); }
    void mouseButton(double x, double y, int button, boolean down) {
        input(new WebMouseEvent(down ? WebMouseEvent.Type.DOWN : WebMouseEvent.Type.UP, physical(x), physical(y), button));
    }
    void mouseScroll(double x, double y, double deltaX, double deltaY) {
        input(new WebScrollEvent(physical(x), physical(y), deltaX, deltaY));
    }
    void key(int keyCode, int scanCode, int modifiers, boolean down) {
        input(new WebKeyEvent(down ? WebKeyEvent.Type.DOWN : WebKeyEvent.Type.UP, keyCode, scanCode, modifiers));
    }
    void text(String text, boolean composition, boolean committed) { input(new WebTextInputEvent(text, composition, committed)); }
    void focus(boolean focused) {
        if (view != null) view.focus(focused);
        input(new WebFocusEvent(focused));
    }

    NeoForgeRenderableSurface surface() { return surface; }
    boolean isClosed() { return closed; }

    private void input(dev.qingmo.mcwebui.input.WebInputEvent event) {
        if (!closed && surface != null) surface.input(event);
    }

    private Map<String, Object> diagnostics() {
        LinkedHashMap<String, Object> info = new LinkedHashMap<>();
        info.put("targetId", "neoforge-1.21.1");
        info.put("loader", "NeoForge");
        info.put("minecraftVersion", "1.21.1");
        info.put("javaVersion", 21);
        info.put("browserBackend", "CinemaMod MCEF");
        info.put("browserVersion", "2.1.6-1.21.1");
        info.put("minecraftGuiWidth", minecraftGuiWidth);
        info.put("minecraftGuiHeight", minecraftGuiHeight);
        info.put("browserViewportWidth", surface == null ? 0 : surface.width());
        info.put("browserViewportHeight", surface == null ? 0 : surface.height());
        info.put("guiScale", guiScale);
        info.put("paintCallbacks", surface == null ? 0L : surface.metrics().paintCallbacks());
        info.put("framebufferWidth", surface == null ? 0 : surface.metrics().width());
        info.put("framebufferHeight", surface == null ? 0 : surface.metrics().height());
        info.put("estimatedPaintBytes", surface == null ? 0L : surface.metrics().estimatedPaintBytes());
        info.put("viewState", view == null ? "CLOSED" : view.state().lifecycle().name());
        info.put("sessionState", closed ? "CLOSED" : "ACTIVE");
        return Map.copyOf(info);
    }

    private double physical(double guiCoordinate) { return guiCoordinate * guiScale; }
    private static int physical(int size, double scale) { return Math.max(1, (int) Math.round(size * scale)); }
    private static int requireDimension(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
    private static double requireScale(double value) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("guiScale must be positive");
        return value;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (view != null) view.setVisible(false);
        if (surface != null) surface.close();
        if (view != null) {
            demo.removeBridge(view.bridge());
            view.close();
        }
        runtime.close();
        backend.close();
    }
}
