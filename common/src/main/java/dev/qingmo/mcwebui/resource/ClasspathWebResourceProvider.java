package dev.qingmo.mcwebui.resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Resolves mcui resources directly from a mod/JAR classpath; it never extracts files to disk. */
public final class ClasspathWebResourceProvider implements WebResourceProvider {
    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("woff2", "font/woff2")
    );

    private final ClassLoader classLoader;
    private final String root;

    public ClasspathWebResourceProvider(ClassLoader classLoader, String root) {
        this.classLoader = classLoader == null ? ClasspathWebResourceProvider.class.getClassLoader() : classLoader;
        this.root = root == null ? "web" : root.replaceAll("/+$", "");
    }

    public ClasspathWebResourceProvider() { this(null, "web"); }

    @Override
    public WebResourceResponse resolve(WebResourceRequest request) {
        if (request == null || !"GET".equals(request.method())) return WebResourceResponse.notFound();
        WebResourceLocation location;
        try { location = request.location(); } catch (RuntimeException ex) { return WebResourceResponse.notFound(); }
        String resourceName = root + "/" + location.namespace() + location.path();
        try (InputStream stream = classLoader.getResourceAsStream(resourceName)) {
            if (stream == null) return WebResourceResponse.notFound();
            byte[] bytes = stream.readAllBytes();
            return new WebResourceResponse(200, mimeType(location.path()), bytes);
        } catch (IOException ex) {
            return new WebResourceResponse(500, "text/plain; charset=utf-8", new byte[0]);
        }
    }

    private static String mimeType(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return "application/octet-stream";
        return MIME_TYPES.getOrDefault(path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT), "application/octet-stream");
    }
}
