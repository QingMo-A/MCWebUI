package dev.qingmo.mcwebui.runtime;

import dev.qingmo.mcwebui.bridge.WebBridge;
import dev.qingmo.mcwebui.state.WebStateStore;

public interface WebView extends AutoCloseable {
    String id();
    WebViewConfig config();
    WebViewState state();
    WebBridge bridge();
    WebStateStore stateStore();
    void initialize();
    void setVisible(boolean visible);
    void resize(int width, int height);
    void focus(boolean focused);
    @Override
    void close();
}
