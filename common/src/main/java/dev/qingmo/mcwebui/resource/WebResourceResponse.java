package dev.qingmo.mcwebui.resource;

public record WebResourceResponse(int status, String mimeType, byte[] body) {
    public WebResourceResponse {
        if (status < 100 || status > 599) throw new IllegalArgumentException("Invalid HTTP status");
        if (mimeType == null || mimeType.isBlank()) throw new IllegalArgumentException("mimeType must not be blank");
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() { return body.clone(); }

    public static WebResourceResponse notFound() { return new WebResourceResponse(404, "text/plain", new byte[0]); }
}
