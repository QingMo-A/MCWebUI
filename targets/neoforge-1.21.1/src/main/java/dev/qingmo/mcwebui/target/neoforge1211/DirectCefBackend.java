package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.backend.BrowserBackend;
import dev.qingmo.mcwebui.backend.FrameMetrics;
import dev.qingmo.mcwebui.bridge.WebBridge;
import dev.qingmo.mcwebui.input.WebInputEvent;
import dev.qingmo.mcwebui.input.WebFocusEvent;
import dev.qingmo.mcwebui.input.WebKeyEvent;
import dev.qingmo.mcwebui.input.WebMouseEvent;
import dev.qingmo.mcwebui.input.WebScrollEvent;
import dev.qingmo.mcwebui.input.WebTextInputEvent;
import dev.qingmo.mcwebui.runtime.WebViewConfig;
import dev.qingmo.mcwebui.nativecef.DirectCefRuntime;
import dev.qingmo.mcwebui.nativecef.ValidatedDirectCefRuntime;

import java.nio.file.Path;
import java.util.Objects;

/** Experimental Windows-only direct CEF adapter; explicit selection never falls back to MCEF. */
final class DirectCefBackend implements BrowserBackend {
    private final long parentWindow;
    private final ValidatedDirectCefRuntime validatedRuntime;
    private final Path cacheDirectory;
    private final String url;
    private final int targetHz;

    DirectCefBackend(long parentWindow, ValidatedDirectCefRuntime validatedRuntime,
                     Path cacheDirectory, String url, int targetHz) {
        this.parentWindow = parentWindow;
        this.validatedRuntime = Objects.requireNonNull(validatedRuntime, "validatedRuntime");
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        this.url = Objects.requireNonNull(url, "url");
        this.targetHz = clampTargetHz(targetHz);
    }

    int targetHz() { return targetHz; }
    ValidatedDirectCefRuntime validatedRuntime() { return validatedRuntime; }

    static int clampTargetHz(int targetHz) { return Math.max(1, Math.min(144, targetHz)); }

    @Override public DirectCefRenderableSurface createSurface(WebViewConfig config, WebBridge bridge) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(bridge, "bridge");
        String requestedUrl = url.isBlank() ? config.origin().asUri() + config.initialPath() : url;
        DirectCefRuntime runtime = DirectCefRuntime.create(validatedRuntime, requestedUrl, cacheDirectory,
                parentWindow, config.width(), config.height(), targetHz);
        System.out.println("[MCWebUI] Direct CEF runtime ready diagnostics=" + runtime.diagnosticsJson());
        return new Surface(runtime, bridge, config.width(), config.height());
    }

    private static final class Surface implements DirectCefRenderableSurface {
        private final DirectCefRuntime runtime;
        private final FrameMetrics metrics = new FrameMetrics();
        private final DirectBridgeHost bridgeHost;
        private volatile int width;
        private volatile int height;
        private volatile boolean closed;
        private int pressedButtons;
        private volatile long navigationEpoch;
        private boolean handshakeLogged;
        Surface(DirectCefRuntime runtime, WebBridge bridge, int width, int height) {
            this.runtime=runtime;
            this.navigationEpoch = runtime.bridgeNavigationEpoch();
            this.bridgeHost = new DirectBridgeHost(bridge,
                    encoded -> runtime.deliverBridgeMessage(navigationEpoch, encoded));
            this.width=width;
            this.height=height;
        }
        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public void load(String url) { ensureOpen(); }
        @Override public void resize(int width, int height) { ensureOpen(); if(!runtime.resize(width,height)) throw new IllegalStateException("Direct CEF resize failed"); this.width=width; this.height=height; }
        @Override public void input(WebInputEvent event) {
            ensureOpen();
            if (event instanceof WebMouseEvent mouse) {
                switch (mouse.type()) {
                    case MOVE -> runtime.mouseMove((int)Math.round(mouse.x()), (int)Math.round(mouse.y()), 0, false);
                    case DOWN -> {
                        pressedButtons |= mouseButtonMask(mouse.button());
                        runtime.setMouseButtons(pressedButtons);
                        runtime.mouseButton((int)Math.round(mouse.x()), (int)Math.round(mouse.y()), 0, mouse.button(), false, 1);
                    }
                    case UP -> {
                        pressedButtons &= ~mouseButtonMask(mouse.button());
                        runtime.setMouseButtons(pressedButtons);
                        runtime.mouseButton((int)Math.round(mouse.x()), (int)Math.round(mouse.y()), 0, mouse.button(), true, 1);
                    }
                }
            } else if (event instanceof WebScrollEvent scroll) {
                runtime.mouseWheel((int)Math.round(scroll.x()), (int)Math.round(scroll.y()), 0,
                        (int)Math.round(scroll.deltaX()), (int)Math.round(scroll.deltaY()));
            } else if (event instanceof WebKeyEvent key) {
                int message = key.type() == WebKeyEvent.Type.DOWN ? 0x0100 : 0x0101;
                runtime.key(message, key.keyCode(), key.scanCode());
            } else if (event instanceof WebTextInputEvent text) {
                runtime.text(text.text());
            } else if (event instanceof WebFocusEvent focus) {
                runtime.setFocus(focus.focused());
            }
        }
        @Override public FrameMetrics metrics() { return metrics; }
        @Override public void pumpBridge() {
            ensureOpen();
            long currentEpoch = runtime.bridgeNavigationEpoch();
            if (currentEpoch != navigationEpoch) {
                navigationEpoch = currentEpoch;
                bridgeHost.resetSession();
                handshakeLogged = false;
            }
            for (int drained = 0; drained < 64; drained++) {
                DirectCefRuntime.BridgeQuery query = runtime.pollBridgeQuery();
                if (query == null) return;
                try {
                    runtime.completeBridgeQuery(query.id(), bridgeHost.handle(query.request()));
                    if (!handshakeLogged && bridgeHost.connected()) {
                        handshakeLogged = true;
                        System.out.println("[MCWebUI] Direct CEF bridge handshake completed");
                    }
                } catch (RuntimeException ex) {
                    runtime.failBridgeQuery(query.id(), 500, "Bridge host failed");
                }
            }
        }
        @Override public boolean bridgeReady() { return bridgeHost.connected(); }
        @Override public boolean supportsExternalFrames() { return true; }
        @Override public boolean requestExternalFrame() {
            ensureOpen();
            boolean requested = runtime.requestFrame();
            if (requested) metrics.recordExternalRequest();
            return requested;
        }
        @Override public void setVisible(boolean visible) {
            ensureOpen();
            if (!visible) {
                pressedButtons = 0;
                runtime.setMouseButtons(0);
                runtime.setFocus(false);
            }
            runtime.setVisible(visible);
        }
        @Override public void refreshGlContext() { ensureOpen(); runtime.refreshGlContext(); }
        @Override public boolean beginRenderFrame() { ensureOpen(); return runtime.beginRenderFrame(); }
        @Override public void endRenderFrame() { if(!closed) runtime.endRenderFrame(); }
        @Override public void markFrameDrawn() { if(!closed) runtime.markFrameDrawn(); }
        @Override public int textureId() { return runtime.textureId(); }
        @Override public SurfaceAlphaMode alphaMode() { return SurfaceAlphaMode.PREMULTIPLIED; }
        @Override public boolean yFlipped() { return runtime.yFlipped(); }
        @Override public String runtimeDiagnosticsJson() { return runtime.diagnosticsJson(); }
        @Override public void close() { if(closed)return; closed=true; bridgeHost.close(); runtime.close(); }
        private void ensureOpen(){ if(closed) throw new IllegalStateException("Direct CEF surface is closed"); }
        private static int mouseButtonMask(int button) {
            return switch (button) { case 0 -> 1; case 1 -> 2; case 2 -> 4; default -> 0; };
        }
    }
}
