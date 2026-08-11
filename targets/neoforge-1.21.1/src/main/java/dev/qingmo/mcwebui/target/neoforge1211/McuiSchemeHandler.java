package dev.qingmo.mcwebui.target.neoforge1211;

import org.cef.callback.CefCallback;
import org.cef.handler.CefResourceHandler;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Map;

/** CEF request handler for mcui:// resources stored directly in the mod JAR. */
final class McuiSchemeHandler implements CefResourceHandler {
    private final String url;
    private InputStream stream;
    private String mime = "application/octet-stream";

    McuiSchemeHandler(String url) { this.url = url; }

    @Override
    public boolean processRequest(CefRequest request, CefCallback callback) {
        try {
            URI uri = URI.create(url);
            if (!"mcui".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                callback.cancel(); return false;
            }
            String path = uri.getRawPath();
            if (path == null || path.contains("\\") || path.contains("..") || path.contains("%2e")
                    || path.contains("%2E") || path.contains("%2f") || path.contains("%2F")) {
                callback.cancel(); return false;
            }
            String resource = "web/" + uri.getHost().toLowerCase(Locale.ROOT) + path;
            stream = McuiSchemeHandler.class.getClassLoader().getResourceAsStream(resource);
            if (stream == null) { callback.cancel(); return false; }
            mime = mime(path);
            callback.Continue();
            return true;
        } catch (RuntimeException ex) {
            callback.cancel(); return false;
        }
    }

    @Override
    public void getResponseHeaders(CefResponse response, IntRef contentLength, StringRef redirectUrl) {
        response.setStatus(200);
        response.setStatusText("OK");
        response.setMimeType(mime);
        contentLength.set(0); // chunked response; CEF reads until readResponse returns false
    }

    @Override
    public boolean readResponse(byte[] output, int bytesToRead, IntRef bytesRead, CefCallback callback) {
        if (stream == null) { bytesRead.set(0); return false; }
        try {
            int read = stream.read(output, 0, bytesToRead);
            if (read <= 0) { stream.close(); bytesRead.set(0); return false; }
            bytesRead.set(read); return true;
        } catch (IOException ex) {
            bytesRead.set(0); return false;
        }
    }

    @Override
    public void cancel() {
        try { if (stream != null) stream.close(); } catch (IOException ignored) { }
    }

    private static String mime(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return Map.of("html", "text/html", "js", "text/javascript", "css", "text/css",
                "json", "application/json", "svg", "image/svg+xml").getOrDefault(extension, "application/octet-stream");
    }
}
