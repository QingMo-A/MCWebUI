package dev.qingmo.mcwebui.resource;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** A normalized, traversal-safe mcui:// resource location. */
public record WebResourceLocation(String namespace, String path) {
    public WebResourceLocation {
        namespace = Objects.requireNonNull(namespace, "namespace").toLowerCase(java.util.Locale.ROOT);
        path = normalizePath(path);
        if (!namespace.matches("[a-z0-9][a-z0-9._-]*")) throw new IllegalArgumentException("Invalid namespace");
    }

    public static WebResourceLocation parse(String uri) {
        Objects.requireNonNull(uri, "uri");
        try {
            URI parsed = new URI(uri);
            if (!"mcui".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null
                    || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
                throw new IllegalArgumentException("Expected mcui://<namespace>/<path>");
            }
            return new WebResourceLocation(parsed.getHost(), parsed.getRawPath());
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid mcui resource URI", ex);
        }
    }

    public String uri() { return "mcui://" + namespace + path; }

    private static String normalizePath(String raw) {
        Objects.requireNonNull(raw, "path");
        if (!raw.startsWith("/") || raw.indexOf('\\') >= 0 || raw.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Resource path must be absolute and use '/'");
        }
        String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        if (!decoded.equals(raw) && (decoded.contains("..") || decoded.indexOf('\\') >= 0)) {
            throw new IllegalArgumentException("Encoded traversal is not allowed");
        }
        String[] segments = decoded.split("/", -1);
        StringBuilder normalized = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty()) continue;
            if (segment.equals(".") || segment.equals("..") || segment.contains("\0")) {
                throw new IllegalArgumentException("Path traversal is not allowed");
            }
            normalized.append('/').append(segment);
        }
        if (normalized.isEmpty()) normalized.append("/");
        return normalized.toString();
    }
}
