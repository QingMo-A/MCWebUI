package dev.qingmo.mcwebui.backend;

import dev.qingmo.mcwebui.input.WebInputEvent;

public interface BrowserSurface extends AutoCloseable {
    int width();
    int height();
    void load(String url);
    void resize(int width, int height);
    void input(WebInputEvent event);
    FrameMetrics metrics();
    @Override
    void close();
}
