package dev.qingmo.mcwebui;

import dev.qingmo.mcwebui.api.MCWebUIApi;
import dev.qingmo.mcwebui.api.WebAppDefinition;
import dev.qingmo.mcwebui.api.WebAppId;
import dev.qingmo.mcwebui.api.WebAppRegistry;
import dev.qingmo.mcwebui.api.WebScreenOptions;
import dev.qingmo.mcwebui.api.WebViewportPolicy;
import dev.qingmo.mcwebui.bridge.BridgeDispatcher;
import dev.qingmo.mcwebui.resource.WebResourceProvider;
import dev.qingmo.mcwebui.resource.WebResourceResponse;
import dev.qingmo.mcwebui.security.WebPermissionPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.Consumer;
import dev.qingmo.mcwebui.bridge.WebBridge;

import static org.junit.jupiter.api.Assertions.*;

class WebAppApiTest {
    private static final WebResourceProvider RESOURCE = request ->
            new WebResourceResponse(200, "text/html; charset=utf-8",
                    "<html></html>".getBytes(StandardCharsets.UTF_8));

    @Test
    void developerPreviewVersionIsExplicit() {
        assertEquals(1, MCWebUIApi.API_VERSION);
        assertEquals("DEVELOPER_PREVIEW", MCWebUIApi.RELEASE_CHANNEL);
    }

    @Test
    void appIdHasStableCanonicalCaseAndEquality() {
        WebAppId first = WebAppId.parse("EconomySystem:Shop/Overview");
        WebAppId second = new WebAppId("economysystem", "shop/overview");

        assertEquals(second, first);
        assertEquals("economysystem:shop/overview", first.value());
        assertEquals(first, WebAppId.of("ECONOMYSYSTEM", "SHOP/OVERVIEW"));
        assertThrows(IllegalArgumentException.class, () -> WebAppId.parse("economysystem"));
        assertThrows(IllegalArgumentException.class, () -> WebAppId.parse("economysystem:shop:other"));
        assertThrows(IllegalArgumentException.class, () -> WebAppId.parse("economysystem:../secret"));
        assertThrows(IllegalArgumentException.class, () -> WebAppId.parse("economysystem:商店"));
        assertThrows(IllegalArgumentException.class, () -> WebAppId.parse("economysystem:shop:main"));
        assertThrows(IllegalArgumentException.class, () -> WebAppId.parse("economysystem:/shop"));
        assertThrows(IllegalArgumentException.class, () -> WebAppId.parse("economysystem:shop\\main"));
    }

    @Test
    void screenOptionsExposeOnlyBackendNeutralSemantics() {
        WebScreenOptions defaults = WebScreenOptions.defaults();
        assertFalse(defaults.pauseGame());
        assertTrue(defaults.closeOnEsc());
        assertTrue(defaults.transparent());
        assertEquals(WebViewportPolicy.GUI, defaults.viewportPolicy());

        WebScreenOptions custom = WebScreenOptions.builder()
                .pauseGame(true)
                .closeOnEsc(false)
                .transparent(false)
                .viewport(WebViewportPolicy.FRAMEBUFFER)
                .build();
        assertTrue(custom.pauseGame());
        assertFalse(custom.closeOnEsc());
        assertFalse(custom.transparent());
        assertEquals(WebViewportPolicy.FRAMEBUFFER, custom.viewport());
        assertEquals(custom, custom.toBuilder().build());
    }

    @Test
    void definitionValidatesEntryAndResolvesThroughOwnProvider() {
        BridgeDispatcher dispatcher = new BridgeDispatcher();
        WebPermissionPolicy policy = new WebPermissionPolicy(Set.of(), false);
        WebScreenOptions options = WebScreenOptions.builder().transparent(false).build();
        Consumer<WebBridge> initializer = ignored -> { };
        WebAppDefinition definition = WebAppDefinition.builder("economysystem:shop")
                .resources(RESOURCE)
                .entry("index.html")
                .bridge(dispatcher)
                .permissions(policy)
                .screenOptions(options)
                .onBridgeCreated(initializer)
                .build();

        assertSame(dispatcher, definition.bridge());
        assertSame(policy, definition.permissions());
        assertSame(options, definition.screenOptions());
        assertSame(initializer, definition.bridgeInitializer());
        assertEquals("index.html", definition.entry());
        assertEquals("shop", definition.id().path());
        assertEquals("/shop/index.html", definition.entryLocation().path());
        assertEquals("/shop/assets/app.js", definition.resourceLocation("assets/app.js").path());
        assertEquals(200, definition.resolveEntry().status());

        assertThrows(NullPointerException.class, () -> WebAppDefinition.builder("demo:app")
                .entry("index.html").build());
        assertThrows(IllegalArgumentException.class, () -> WebAppDefinition.builder("demo:app")
                .resources(RESOURCE).entry("../index.html").build());
        assertThrows(IllegalArgumentException.class, () -> WebAppDefinition.builder("demo:app")
                .resources(RESOURCE).entry("/index.html").build());
        assertThrows(IllegalArgumentException.class, () -> WebAppDefinition.builder("demo:app")
                .resources(RESOURCE).entry("index%2ehtml").build());
        assertThrows(IllegalArgumentException.class, () -> WebAppDefinition.builder("demo:app")
                .resources(RESOURCE).entry("index.html").build().resourceLocation("../secret"));
    }

    @Test
    void definitionsCanRegisterAnIsolatedBridgePerApp() {
        BridgeDispatcher firstBridge = new BridgeDispatcher();
        BridgeDispatcher secondBridge = new BridgeDispatcher();
        firstBridge.register("first.ping", request -> "first");
        secondBridge.register("second.ping", request -> "second");
        WebAppDefinition first = WebAppDefinition.builder("sample:first")
                .resources(RESOURCE).entry("index.html").bridge(firstBridge).build();
        WebAppDefinition second = WebAppDefinition.builder("sample:second")
                .resources(RESOURCE).entry("index.html").bridge(secondBridge).build();

        assertNotSame(first.bridge(), second.bridge());
        assertNotSame(first.permissions(), second.permissions());
        assertTrue(first.bridge().contains("first.ping"));
        assertFalse(first.bridge().contains("second.ping"));
        assertTrue(second.bridge().contains("second.ping"));
        assertFalse(second.bridge().contains("first.ping"));
    }

    @Test
    void registryIsIdempotentOnlyForTheSameDefinitionAndNeverLastWriterWins() {
        WebAppRegistry registry = new WebAppRegistry();
        WebAppDefinition first = WebAppDefinition.builder("sample:app")
                .resources(RESOURCE).entry("index.html").build();
        WebAppDefinition conflicting = WebAppDefinition.builder("SAMPLE:APP")
                .resources(RESOURCE).entry("other.html").build();

        assertSame(first, registry.register(first));
        assertSame(first, registry.register(first));
        assertSame(first, registry.lookup("sample:app"));
        assertEquals(1, registry.size());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> registry.register(conflicting));
        assertTrue(error.getMessage().contains("sample:app"));
        assertSame(first, registry.lookup(WebAppId.parse("SAMPLE:APP")));
    }
}
