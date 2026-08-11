package dev.qingmo.mcwebui.target.neoforge1211;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import dev.qingmo.mcwebui.backend.BrowserBackend;
import dev.qingmo.mcwebui.backend.BrowserSurface;
import dev.qingmo.mcwebui.backend.BrowserSurfaceListener;
import dev.qingmo.mcwebui.backend.FrameMetrics;
import dev.qingmo.mcwebui.input.WebInputEvent;
import dev.qingmo.mcwebui.input.WebKeyEvent;
import dev.qingmo.mcwebui.input.WebMouseEvent;
import dev.qingmo.mcwebui.input.WebScrollEvent;
import dev.qingmo.mcwebui.input.WebTextInputEvent;
import dev.qingmo.mcwebui.runtime.WebViewConfig;

import java.util.Objects;

/** Real CinemaMod MCEF backend adapter; all MCEF types stay in the NeoForge target. */
public final class NeoForgeMcefBackend implements BrowserBackend {
    private static final String ARTIFACT = "com.cinemamod:mcef-neoforge:2.1.6-1.21.1";

    @Override
    public BrowserSurface createSurface(WebViewConfig config, BrowserSurfaceListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (!MCEF.isInitialized()) throw new IllegalStateException("MCEF is not initialized");
        String url = config.origin().asUri() + config.initialPath();
        McefSurface surface = new McefSurface(url, config.width(), config.height());
        return surface;
    }

    public static String artifact() { return ARTIFACT; }

    private static final class McefSurface implements BrowserSurface {
        private final InstrumentedMcefBrowser browser;
        private final FrameMetrics metrics = new FrameMetrics();
        private int width;
        private int height;

        private McefSurface(String url, int width, int height) {
            this.width = width;
            this.height = height;
            this.browser = new InstrumentedMcefBrowser(url, true, metrics);
            this.browser.resize(width, height);
        }

        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public void load(String url) { browser.loadURL(url); }
        @Override public void resize(int width, int height) { this.width = width; this.height = height; browser.resize(width, height); }
        @Override public FrameMetrics metrics() { return metrics; }
        @Override public int textureId() { return browser.getRenderer().getTextureID(); }

        @Override
        public void input(WebInputEvent event) {
            if (event instanceof WebMouseEvent mouse) {
                switch (mouse.type()) {
                    case MOVE -> browser.sendMouseMove((int) mouse.x(), (int) mouse.y());
                    case DOWN -> browser.sendMousePress((int) mouse.x(), (int) mouse.y(), mouse.button());
                    case UP -> browser.sendMouseRelease((int) mouse.x(), (int) mouse.y(), mouse.button());
                }
            } else if (event instanceof WebScrollEvent scroll) {
                browser.sendMouseWheel((int) scroll.x(), (int) scroll.y(), scroll.deltaY(), (int) scroll.deltaX());
            } else if (event instanceof WebKeyEvent key) {
                if (key.type() == WebKeyEvent.Type.DOWN) browser.sendKeyPress(key.keyCode(), 0, key.modifiers());
                else browser.sendKeyRelease(key.keyCode(), 0, key.modifiers());
            } else if (event instanceof WebTextInputEvent text) {
                text.text().codePoints().forEach(codePoint -> browser.sendKeyTyped((char) codePoint, 0));
            }
        }

        @Override public void close() { browser.close(); }
    }

    /**
     * MCEF's renderer already uploads CEF paint buffers into an OpenGL texture. Subclassing
     * the official browser preserves that path while exposing paint/upload counters to the
     * common instrumentation surface without copying native pixel buffers into Java arrays.
     */
    private static final class InstrumentedMcefBrowser extends MCEFBrowser {
        private final FrameMetrics metrics;

        private InstrumentedMcefBrowser(String url, boolean transparent, FrameMetrics metrics) {
            super(MCEF.getClient(), url, transparent);
            this.metrics = metrics;
            setCloseAllowed();
            createImmediately();
        }

        @Override
        public void onPaint(org.cef.browser.CefBrowser browser, boolean popup,
                            java.awt.Rectangle[] dirtyRects, java.nio.ByteBuffer buffer,
                            int width, int height) {
            super.onPaint(browser, popup, dirtyRects, buffer, width, height);
            if (!popup) {
                metrics.recordPaint(width, height);
                metrics.recordUpload((long) width * height * 4L);
            }
        }
    }
}
