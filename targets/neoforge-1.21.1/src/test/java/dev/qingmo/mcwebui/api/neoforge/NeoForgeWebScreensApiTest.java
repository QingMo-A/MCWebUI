package dev.qingmo.mcwebui.api.neoforge;

import dev.qingmo.mcwebui.api.WebAppDefinition;
import dev.qingmo.mcwebui.api.WebAppRegistry;
import dev.qingmo.mcwebui.resource.WebResourceResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NeoForgeWebScreensApiTest {
    @AfterEach void clearRegistry() { WebAppRegistry.process().clear(); }

    @Test void facadeRegistersDefinitionsWithoutBackendTypes() {
        WebAppDefinition app = WebAppDefinition.builder("consumer:facade")
                .resources(request -> new WebResourceResponse(200, "text/html", new byte[0]))
                .entry("index.html")
                .build();

        assertEquals(1, MCWebUIClient.API_VERSION);
        assertSame(app, MCWebUIClient.register(app));
        assertSame(app, WebAppRegistry.process().require(app.id()));
    }
}
