package dev.qingmo.mcwebui.api;

import java.util.Locale;
import java.util.Objects;

/**
 * Stable identity for a registered WebApp.
 *
 * <p>Identity is deliberately not a URL.  The canonical form is a strict
 * {@code namespace:path} pair.  Namespace and path are both normalized to
 * lower case using {@link Locale#ROOT}; this makes equality deterministic
 * across loaders, operating systems, and class loaders.  Resource file names
 * remain case-sensitive when resolved by a {@code WebResourceProvider}; only
 * the app identity is case-insensitive.</p>
 */
public record WebAppId(String namespace, String path) {
    private static final String NAMESPACE_PATTERN = "[a-z0-9][a-z0-9._-]*";
    private static final String PATH_SEGMENT_PATTERN = "[a-z0-9._-]+";

    public WebAppId {
        namespace = canonicalPart(namespace, "namespace");
        path = canonicalPath(path);
        if (!namespace.matches(NAMESPACE_PATTERN)) {
            throw new IllegalArgumentException("Invalid WebApp namespace: " + namespace);
        }
    }

    /** Parses the canonical {@code namespace:path} spelling. */
    public static WebAppId parse(String value) {
        Objects.requireNonNull(value, "value");
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException("WebApp id must not contain surrounding whitespace");
        }
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':') || separator == value.length() - 1) {
            throw new IllegalArgumentException("WebApp id must use namespace:path");
        }
        return new WebAppId(value.substring(0, separator), value.substring(separator + 1));
    }

    /** Alias for callers that prefer a factory-style spelling. */
    public static WebAppId of(String namespace, String path) {
        return new WebAppId(namespace, path);
    }

    /** Canonical namespace:path value suitable for logs and registry keys. */
    public String value() {
        return namespace + ":" + path;
    }

    /** Alias retained for callers that use URL-like naming. */
    public String asString() {
        return value();
    }

    @Override
    public String toString() {
        return value();
    }

    private static String canonicalPart(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || !value.equals(value.trim()) || containsForbidden(value)) {
            throw new IllegalArgumentException("Invalid WebApp " + label + ": " + value);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String canonicalPath(String value) {
        value = canonicalPart(value, "path");
        if (value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
            throw new IllegalArgumentException("WebApp path must not start/end with '/': " + value);
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || !segment.matches(PATH_SEGMENT_PATTERN)) {
                throw new IllegalArgumentException("Invalid WebApp path: " + value);
            }
        }
        return value;
    }

    private static boolean containsForbidden(String value) {
        return value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0
                || value.indexOf('?') >= 0 || value.indexOf('#') >= 0
                || value.indexOf('@') >= 0 || value.indexOf(':') >= 0
                || value.indexOf(' ') >= 0;
    }
}
