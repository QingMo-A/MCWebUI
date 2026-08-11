package dev.qingmo.mcwebui.resource;

import java.util.Map;

public record WebResourceRequest(WebResourceLocation location, String method, Map<String, String> headers) {
    public WebResourceRequest {
        if (location == null) throw new NullPointerException("location");
        method = method == null ? "GET" : method.toUpperCase(java.util.Locale.ROOT);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
