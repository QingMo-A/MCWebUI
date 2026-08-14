package dev.qingmo.mcwebui.api;

import dev.qingmo.mcwebui.bridge.BridgeDispatcher;
import dev.qingmo.mcwebui.bridge.WebBridge;
import dev.qingmo.mcwebui.resource.WebResourceLocation;
import dev.qingmo.mcwebui.resource.WebResourceProvider;
import dev.qingmo.mcwebui.resource.WebResourceRequest;
import dev.qingmo.mcwebui.resource.WebResourceResponse;
import dev.qingmo.mcwebui.security.WebPermissionPolicy;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Immutable, backend-neutral declaration of one consumer WebApp.
 *
 * <p>The definition owns the app identity, resource provider and entry path,
 * while reusing the common bridge dispatcher and permission policy semantics.
 * Targets adapt this declaration to MCEF or Direct CEF without exposing those
 * implementation types to the consumer.</p>
 */
public final class WebAppDefinition {
    public static final int API_VERSION = MCWebUIApi.API_VERSION;

    private final WebAppId id;
    private final WebResourceProvider resources;
    private final String entry;
    private final BridgeDispatcher bridge;
    private final WebPermissionPolicy permissions;
    private final WebScreenOptions screenOptions;
    private final Consumer<WebBridge> bridgeInitializer;

    private WebAppDefinition(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.resources = Objects.requireNonNull(builder.resources, "resources");
        this.entry = validateEntry(builder.entry);
        this.bridge = Objects.requireNonNull(builder.bridge, "bridge");
        this.permissions = Objects.requireNonNull(builder.permissions, "permissions");
        this.screenOptions = Objects.requireNonNull(builder.screenOptions, "screenOptions");
        this.bridgeInitializer = Objects.requireNonNull(builder.bridgeInitializer, "bridgeInitializer");
    }

    public static Builder builder(String id) {
        return builder(WebAppId.parse(id));
    }

    public static Builder builder(WebAppId id) {
        return new Builder(Objects.requireNonNull(id, "id"));
    }

    public WebAppId id() {
        return id;
    }

    public WebResourceProvider resources() {
        return resources;
    }

    public WebResourceProvider resourceProvider() {
        return resources;
    }

    /** Canonical entry path without a leading slash, e.g. {@code index.html}. */
    public String entry() {
        return entry;
    }

    public String entryPath() {
        return entry;
    }

    public BridgeDispatcher bridge() {
        return bridge;
    }

    public BridgeDispatcher bridgeDispatcher() {
        return bridge;
    }

    public WebPermissionPolicy permissions() {
        return permissions;
    }

    public WebPermissionPolicy permissionPolicy() {
        return permissions;
    }

    public WebScreenOptions screenOptions() {
        return screenOptions;
    }

    public WebScreenOptions options() {
        return screenOptions;
    }

    /** Called once for each created app session, before the page can handshake. */
    public Consumer<WebBridge> bridgeInitializer() {
        return bridgeInitializer;
    }

    /** Resolves the app's entry resource through its provider. */
    public WebResourceResponse resolveEntry() {
        return resources.resolve(new WebResourceRequest(entryLocation(), "GET", Map.of()));
    }

    /**
     * Converts a relative app resource into the shared namespace/path location.
     * The app path is part of the location so two apps in one namespace cannot
     * accidentally serve each other's resources.
     */
    public WebResourceLocation resourceLocation(String relative) {
        return new WebResourceLocation(id.namespace(), "/" + id.path() + "/" + validateEntry(relative));
    }

    /** Location used by both scheme and loopback target adapters. */
    public WebResourceLocation entryLocation() {
        return resourceLocation(entry);
    }

    @Override
    public String toString() {
        return "WebAppDefinition[" + id + ", entry=" + entry + "]";
    }

    public static final class Builder {
        private final WebAppId id;
        private WebResourceProvider resources;
        private String entry;
        private BridgeDispatcher bridge = new BridgeDispatcher();
        private WebPermissionPolicy permissions = new WebPermissionPolicy();
        private WebScreenOptions screenOptions = WebScreenOptions.defaults();
        private Consumer<WebBridge> bridgeInitializer = ignored -> { };

        private Builder(WebAppId id) {
            this.id = id;
        }

        public Builder resources(WebResourceProvider value) {
            resources = Objects.requireNonNull(value, "resources");
            return this;
        }

        public Builder resourceProvider(WebResourceProvider value) {
            return resources(value);
        }

        public Builder entry(String value) {
            entry = value;
            return this;
        }

        public Builder bridge(BridgeDispatcher value) {
            bridge = Objects.requireNonNull(value, "bridge");
            return this;
        }

        /** Creates an isolated dispatcher and lets the consumer register methods on it. */
        public Builder bridge(Consumer<BridgeDispatcher> registrar) {
            Objects.requireNonNull(registrar, "registrar");
            BridgeDispatcher dispatcher = new BridgeDispatcher();
            registrar.accept(dispatcher);
            bridge = dispatcher;
            return this;
        }

        public Builder permissions(WebPermissionPolicy value) {
            permissions = Objects.requireNonNull(value, "permissions");
            return this;
        }

        public Builder permissionPolicy(WebPermissionPolicy value) {
            return permissions(value);
        }

        public Builder screenOptions(WebScreenOptions value) {
            screenOptions = Objects.requireNonNull(value, "screenOptions");
            return this;
        }

        public Builder options(WebScreenOptions value) {
            return screenOptions(value);
        }

        /** Publishes initial state or retains the session-scoped bridge for host updates. */
        public Builder onBridgeCreated(Consumer<WebBridge> value) {
            bridgeInitializer = Objects.requireNonNull(value, "bridgeInitializer");
            return this;
        }

        public WebAppDefinition build() {
            return new WebAppDefinition(this);
        }
    }

    private static String validateEntry(String value) {
        Objects.requireNonNull(value, "entry");
        if (value.isBlank() || !value.equals(value.trim()) || value.startsWith("/")
                || value.endsWith("/") || value.contains("//") || value.indexOf('\\') >= 0
                || value.indexOf('\0') >= 0 || value.indexOf('?') >= 0 || value.indexOf('#') >= 0
                || value.indexOf(':') >= 0 || value.contains("%")) {
            throw new IllegalArgumentException("Entry must be a relative, traversal-safe resource path");
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Entry must not contain traversal segments");
            }
        }
        return value;
    }
}
