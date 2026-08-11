package dev.qingmo.mcwebui.target.neoforge1211;

import org.cef.callback.CefCallback;
import org.cef.handler.CefResourceHandler;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;

/** CEF request handler for mcui:// resources stored directly in the mod JAR. */
final class McuiSchemeHandler implements CefResourceHandler {
    private final String url;
    private byte[] body = new byte[0];
    private int offset;
    private int status = 404;
    private String statusText = "Not Found";
    private String mime = "application/octet-stream";

    McuiSchemeHandler(String url) { this.url = url; }

    @Override
    public boolean processRequest(CefRequest request, CefCallback callback) {
        try {
            URI uri = URI.create(url);
            if (!"mcui".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return notFound(callback);
            }
            String path = uri.getRawPath();
            if (path == null || path.contains("\\") || path.contains("..") || path.contains("%2e")
                    || path.contains("%2E") || path.contains("%2f") || path.contains("%2F")) {
                return notFound(callback);
            }
            String namespace = namespace(uri.getHost());
            if (namespace == null) return notFound(callback);
            String resource = "web/" + namespace + path;
            try (var stream = McuiSchemeHandler.class.getClassLoader().getResourceAsStream(resource)) {
                if (stream == null) return notFound(callback);
                body = stream.readAllBytes();
            }
            offset = 0;
            mime = mime(path);
            status = 200;
            statusText = "OK";
            callback.Continue();
            return true;
        } catch (IOException | RuntimeException ex) {
            return notFound(callback);
        }
    }

    @Override
    public void getResponseHeaders(CefResponse response, IntRef contentLength, StringRef redirectUrl) {
        response.setStatus(status);
        response.setStatusText(statusText);
        response.setMimeType(mime);
        // The body is buffered, so report its exact length. CEF reserves -1 for unknown
        // streaming responses; zero here is only used for a genuinely empty body.
        contentLength.set(body.length);
    }

    @Override
    public boolean readResponse(byte[] output, int bytesToRead, IntRef bytesRead, CefCallback callback) {
        if (offset >= body.length || bytesToRead <= 0) {
            bytesRead.set(0);
            return false;
        }
        int read = Math.min(bytesToRead, body.length - offset);
        System.arraycopy(body, offset, output, 0, read);
        offset += read;
        bytesRead.set(read);
        return true;
    }

    @Override
    public void cancel() {
        body = new byte[0];
        offset = 0;
    }

    private boolean notFound(CefCallback callback) {
        body = new byte[0];
        offset = 0;
        status = 404;
        statusText = "Not Found";
        mime = "text/plain";
        callback.Continue();
        return true;
    }

    private static String mime(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return Map.of("html", "text/html", "js", "text/javascript", "css", "text/css",
                "json", "application/json", "svg", "image/svg+xml").getOrDefault(extension, "application/octet-stream");
    }

    private static String namespace(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        String suffix = ".mcwebui";
        if (!normalized.endsWith(suffix)) return null;
        String namespace = normalized.substring(0, normalized.length() - suffix.length());
        return namespace.matches("[a-z0-9][a-z0-9-]*") ? namespace : null;
    }
}
