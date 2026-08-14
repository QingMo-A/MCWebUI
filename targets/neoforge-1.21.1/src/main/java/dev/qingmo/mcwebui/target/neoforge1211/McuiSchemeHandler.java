package dev.qingmo.mcwebui.target.neoforge1211;

import org.cef.callback.CefCallback;
import org.cef.handler.CefResourceHandler;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;
import dev.qingmo.mcwebui.api.WebAppDefinition;
import dev.qingmo.mcwebui.bridge.BridgeDispatcher;
import dev.qingmo.mcwebui.resource.WebResourceLocation;
import dev.qingmo.mcwebui.resource.WebResourceRequest;

import java.net.URI;
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
            WebAppDefinition app = NeoForgeWebAppRoutes.resolveHost(uri.getHost());
            // Compatibility for the built-in F8 playground URL used by older tests and
            // existing MCEF sessions. Consumer apps always use hashed registered hosts.
            if (app == null && "playground.mcwebui".equalsIgnoreCase(uri.getHost())) {
                app = NeoForgeBuiltinApps.playgroundForLoader(
                        McuiSchemeHandler.class.getClassLoader(), new BridgeDispatcher());
            }
            if (app == null) return notFound(callback);
            String relative = path.startsWith("/") ? path.substring(1) : path;
            if (relative.isEmpty()) relative = app.entry();
            var response = app.resources().resolve(new WebResourceRequest(
                    new WebResourceLocation(app.id().namespace(),
                            "/" + app.id().path() + "/" + relative),
                    "GET", Map.of()));
            if (response == null || response.status() != 200) return notFound(callback);
            body = response.body();
            offset = 0;
            mime = response.mimeType();
            status = 200;
            statusText = "OK";
            callback.Continue();
            return true;
        } catch (RuntimeException ex) {
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

}
