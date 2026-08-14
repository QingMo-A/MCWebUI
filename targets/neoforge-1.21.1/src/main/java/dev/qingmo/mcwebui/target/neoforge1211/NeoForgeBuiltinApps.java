package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.api.WebAppDefinition;
import dev.qingmo.mcwebui.api.WebAppRegistry;
import dev.qingmo.mcwebui.api.WebScreenOptions;
import dev.qingmo.mcwebui.bridge.BridgeDispatcher;
import dev.qingmo.mcwebui.resource.WebResourceResponse;

import java.io.IOException;
import java.util.Locale;

/** Keeps the F8 playground on the same public registration path as consumer apps. */
final class NeoForgeBuiltinApps {
    static final String PLAYGROUND_ID = "playground:app";

    private NeoForgeBuiltinApps() { }

    static WebAppDefinition playground(BridgeDispatcher dispatcher) {
        WebAppDefinition existing = WebAppRegistry.process().lookup(PLAYGROUND_ID);
        if (existing != null) return existing;
        WebAppDefinition definition = playgroundForLoader(
                NeoForgeBuiltinApps.class.getClassLoader(), dispatcher);
        return WebAppRegistry.process().register(definition);
    }

    static WebAppDefinition playgroundForLoader(ClassLoader loader, BridgeDispatcher dispatcher) {
        return WebAppDefinition.builder(PLAYGROUND_ID)
                .resources(request -> {
                    String prefix = "/app/";
                    String path = request.location().path();
                    if (!request.method().equals("GET") || !path.startsWith(prefix)) {
                        return WebResourceResponse.notFound();
                    }
                    String relative = path.substring(prefix.length());
                    try (var stream = loader.getResourceAsStream("web/playground/" + relative)) {
                        if (stream == null) return WebResourceResponse.notFound();
                        return new WebResourceResponse(200, mime(relative), stream.readAllBytes());
                    } catch (IOException failure) {
                        return WebResourceResponse.notFound();
                    }
                })
                .entry("index.html")
                .bridge(dispatcher)
                .screenOptions(WebScreenOptions.builder()
                        .pauseGame(false).closeOnEsc(true).transparent(true).build())
                .build();
    }

    private static String mime(String path) {
        String extension = path.contains(".")
                ? path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        return switch (extension) {
            case "html" -> "text/html";
            case "js", "mjs" -> "text/javascript";
            case "css" -> "text/css";
            case "json" -> "application/json";
            case "svg" -> "image/svg+xml";
            default -> "application/octet-stream";
        };
    }
}
