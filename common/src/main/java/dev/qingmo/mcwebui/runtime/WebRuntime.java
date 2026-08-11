package dev.qingmo.mcwebui.runtime;

public interface WebRuntime extends AutoCloseable {
    WebView createView(WebViewConfig config);
    @Override
    void close();
}
