package dev.qingmo.mcwebui.resource;

@FunctionalInterface
public interface WebResourceProvider {
    WebResourceResponse resolve(WebResourceRequest request);
}
