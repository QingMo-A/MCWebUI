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

import java.nio.file.Path;
import java.util.Objects;

/** Experimental Windows-only direct CEF adapter; explicit selection never falls back to MCEF. */
final class DirectCefBackend implements BrowserBackend {
    private final long parentWindow;
    private final Path runtimeDirectory;
    private final Path cacheDirectory;
    private final String helperPath;
    private final String url;
    private final int targetHz;

    DirectCefBackend(long parentWindow, Path runtimeDirectory, Path cacheDirectory, String helperPath, String url, int targetHz) {
        this.parentWindow = parentWindow;
        this.runtimeDirectory = Objects.requireNonNull(runtimeDirectory, "runtimeDirectory");
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        this.helperPath = Objects.requireNonNull(helperPath, "helperPath");
        this.url = Objects.requireNonNull(url, "url");
        this.targetHz = Math.max(1, targetHz);
    }

    @Override public DirectCefRenderableSurface createSurface(WebViewConfig config, WebBridge bridge) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(bridge, "bridge");
        String requestedUrl = url.isBlank() ? config.origin().asUri() + config.initialPath() : url;
        DirectCefRuntime runtime = DirectCefRuntime.create(requestedUrl, cacheDirectory.toString(), helperPath,
                parentWindow, config.width(), config.height(), targetHz);
        System.out.println("[MCWebUI] Direct CEF runtime ready diagnostics=" + runtime.diagnosticsJson());
        return new Surface(runtime, config.width(), config.height());
    }

    private static final class Surface implements DirectCefRenderableSurface {
        private final DirectCefRuntime runtime;
        private final FrameMetrics metrics = new FrameMetrics();
        private volatile int width;
        private volatile int height;
        private volatile boolean closed;
        Surface(DirectCefRuntime runtime, int width, int height) { this.runtime=runtime; this.width=width; this.height=height; }
        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public void load(String url) { ensureOpen(); }
        @Override public void resize(int width, int height) { ensureOpen(); if(!runtime.resize(width,height)) throw new IllegalStateException("Direct CEF resize failed"); this.width=width; this.height=height; }
        @Override public void input(WebInputEvent event) {
            ensureOpen();
            if (event instanceof WebMouseEvent mouse) {
                switch (mouse.type()) {
                    case MOVE -> runtime.mouseMove((int)Math.round(mouse.x()), (int)Math.round(mouse.y()), 0, false);
                    case DOWN -> runtime.mouseButton((int)Math.round(mouse.x()), (int)Math.round(mouse.y()), 0, mouse.button(), false, 1);
                    case UP -> runtime.mouseButton((int)Math.round(mouse.x()), (int)Math.round(mouse.y()), 0, mouse.button(), true, 1);
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
        @Override public boolean supportsExternalFrames() { return true; }
        @Override public boolean requestExternalFrame() { ensureOpen(); return runtime.requestFrame(); }
        @Override public boolean beginRenderFrame() { ensureOpen(); return runtime.beginRenderFrame(); }
        @Override public void endRenderFrame() { if(!closed) runtime.endRenderFrame(); }
        @Override public int textureId() { return runtime.textureId(); }
        @Override public String alphaMode() { return runtime.alphaMode(); }
        @Override public boolean yFlipped() { return runtime.yFlipped(); }
        @Override public void close() { if(closed)return; closed=true; runtime.close(); }
        private void ensureOpen(){ if(closed) throw new IllegalStateException("Direct CEF surface is closed"); }
    }
}
