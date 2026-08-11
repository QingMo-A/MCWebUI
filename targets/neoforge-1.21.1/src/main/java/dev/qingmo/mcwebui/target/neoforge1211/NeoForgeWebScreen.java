package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.backend.BrowserSurface;
import dev.qingmo.mcwebui.backend.BrowserSurfaceListener;
import dev.qingmo.mcwebui.backend.PaintFrame;
import dev.qingmo.mcwebui.input.WebFocusEvent;
import dev.qingmo.mcwebui.input.WebKeyEvent;
import dev.qingmo.mcwebui.input.WebMouseEvent;
import dev.qingmo.mcwebui.input.WebScrollEvent;
import dev.qingmo.mcwebui.input.WebTextInputEvent;
import dev.qingmo.mcwebui.runtime.WebView;

/**
 * Thin target screen adapter. A NeoForge {@code Screen} delegates lifecycle and input here in the
 * client entrypoint; no Minecraft class is exposed by common contracts.
 */
public final class NeoForgeWebScreen implements BrowserSurfaceListener, AutoCloseable {
    private final WebView view;
    private final BrowserSurface surface;
    private final NeoForgeTextureUploader textureUploader;
    private volatile boolean closed;

    public NeoForgeWebScreen(WebView view, BrowserSurface surface, NeoForgeTextureUploader textureUploader) {
        this.view = java.util.Objects.requireNonNull(view, "view");
        this.surface = java.util.Objects.requireNonNull(surface, "surface");
        this.textureUploader = java.util.Objects.requireNonNull(textureUploader, "textureUploader");
    }

    public void init() {
        view.initialize();
        view.setVisible(true);
        view.focus(true);
        surface.load(view.config().origin().asUri() + view.config().initialPath());
    }

    public void resize(int guiWidth, int guiHeight, double guiScale) {
        if (guiScale <= 0) throw new IllegalArgumentException("guiScale must be positive");
        int width = Math.max(1, (int) Math.round(guiWidth * guiScale));
        int height = Math.max(1, (int) Math.round(guiHeight * guiScale));
        view.resize(width, height);
        surface.resize(width, height);
    }

    public void mouseMove(double guiX, double guiY, double guiScale) {
        surface.input(new WebMouseEvent(WebMouseEvent.Type.MOVE, guiX * guiScale, guiY * guiScale, -1));
    }

    public void mouseButton(double guiX, double guiY, int button, boolean down, double guiScale) {
        surface.input(new WebMouseEvent(down ? WebMouseEvent.Type.DOWN : WebMouseEvent.Type.UP,
                guiX * guiScale, guiY * guiScale, button));
    }

    public void mouseScroll(double guiX, double guiY, double deltaX, double deltaY, double guiScale) {
        surface.input(new WebScrollEvent(guiX * guiScale, guiY * guiScale, deltaX, deltaY));
    }

    public void key(int keyCode, int modifiers, boolean down) {
        surface.input(new WebKeyEvent(down ? WebKeyEvent.Type.DOWN : WebKeyEvent.Type.UP, keyCode, modifiers));
    }

    public void text(String text, boolean composition, boolean committed) {
        surface.input(new WebTextInputEvent(text, composition, committed));
    }

    public void focus(boolean focused) {
        view.focus(focused);
        surface.input(new WebFocusEvent(focused));
    }

    @Override
    public void onPaint(PaintFrame frame) {
        if (!closed) textureUploader.upload(frame);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        view.setVisible(false);
        surface.close();
        view.close();
    }

    public boolean isClosed() { return closed; }
}
