package dev.qingmo.mcwebui.api;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe process/client-scoped registry of immutable WebApp definitions.
 *
 * <p>Registering the exact same definition object twice is idempotent.  A
 * different definition for an existing ID is always rejected; the registry
 * never silently applies last-writer-wins behavior.</p>
 */
public final class WebAppRegistry {
    private static final WebAppRegistry PROCESS = new WebAppRegistry();

    private final Map<WebAppId, WebAppDefinition> definitions = new ConcurrentHashMap<>();

    /** Returns the default process/client registry used by a target adapter. */
    public static WebAppRegistry process() {
        return PROCESS;
    }

    /** Alias emphasizing that the default registry is client-scoped in targets. */
    public static WebAppRegistry client() {
        return PROCESS;
    }

    /** Alias for integrations that call the process registry global. */
    public static WebAppRegistry global() {
        return PROCESS;
    }

    public WebAppDefinition register(WebAppDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        WebAppDefinition existing = definitions.putIfAbsent(definition.id(), definition);
        if (existing == null || existing == definition) {
            return existing == null ? definition : existing;
        }
        throw new IllegalStateException("WebApp id already registered with a different definition: "
                + definition.id());
    }

    /** Convenience overload for callers that construct definitions inline. */
    public WebAppDefinition register(WebAppDefinition.Builder builder) {
        return register(Objects.requireNonNull(builder, "builder").build());
    }

    public WebAppDefinition lookup(WebAppId id) {
        return id == null ? null : definitions.get(id);
    }

    public WebAppDefinition lookup(String id) {
        return id == null ? null : lookup(WebAppId.parse(id));
    }

    public WebAppDefinition require(WebAppId id) {
        WebAppDefinition definition = lookup(id);
        if (definition == null) throw new IllegalArgumentException("Unknown WebApp id: " + id);
        return definition;
    }

    public WebAppDefinition require(String id) {
        return require(WebAppId.parse(id));
    }

    public boolean contains(WebAppId id) {
        return id != null && definitions.containsKey(id);
    }

    public int size() {
        return definitions.size();
    }

    /** Clears this registry; intended for client shutdown and isolated tests. */
    public void clear() {
        definitions.clear();
    }

    public Map<WebAppId, WebAppDefinition> snapshot() {
        return Map.copyOf(definitions);
    }
}
