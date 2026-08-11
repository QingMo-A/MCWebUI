package dev.qingmo.mcwebui.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/** A parsed browser origin. Only {@code mcui://<namespace>} origins are trusted by default. */
public record WebOrigin(String scheme, String host) {
    public WebOrigin {
        scheme = Objects.requireNonNull(scheme, "scheme").toLowerCase(Locale.ROOT);
        host = Objects.requireNonNull(host, "host").toLowerCase(Locale.ROOT);
        if (scheme.isBlank() || host.isBlank() || host.contains("/") || host.contains("\\")) {
            throw new IllegalArgumentException("Invalid web origin");
        }
    }

    public static WebOrigin parse(String value) {
        Objects.requireNonNull(value, "value");
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Origin must contain only scheme and host: " + value);
            }
            return new WebOrigin(uri.getScheme(), uri.getHost());
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid web origin: " + value, ex);
        }
    }

    public static WebOrigin mcui(String namespace) {
        if (namespace == null || !namespace.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("Invalid mcui namespace: " + namespace);
        }
        return new WebOrigin("mcui", namespace);
    }

    public boolean isTrustedLocal() {
        return scheme.equals("mcui") && host.matches("[a-z0-9][a-z0-9._-]*");
    }

    public String asUri() {
        return scheme + "://" + host;
    }
}
