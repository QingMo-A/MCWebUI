package dev.qingmo.mcwebui.runtime;

public record WebViewState(WebViewLifecycle lifecycle, int width, int height, boolean focused) {
    public WebViewState {
        if (lifecycle == null) throw new NullPointerException("lifecycle");
        if (width < 1 || height < 1) throw new IllegalArgumentException("view dimensions must be positive");
    }
}
