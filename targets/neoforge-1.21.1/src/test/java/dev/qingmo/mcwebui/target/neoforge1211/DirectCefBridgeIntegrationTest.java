package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.api.WebAppDefinition;
import dev.qingmo.mcwebui.api.WebAppRegistry;
import dev.qingmo.mcwebui.bridge.BridgeDispatcher;
import dev.qingmo.mcwebui.bridge.WebBridge;
import dev.qingmo.mcwebui.nativecef.DirectCefRuntime;
import dev.qingmo.mcwebui.nativecef.DirectCefRuntimeDiscovery;
import dev.qingmo.mcwebui.nativecef.ValidatedDirectCefRuntime;
import dev.qingmo.mcwebui.security.WebOrigin;
import dev.qingmo.mcwebui.state.WebStateStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in, real CEF/JNI/Vue/Java bridge acceptance. Normal test runs skip this class. */
class DirectCefBridgeIntegrationTest {
    @Test void registeredProviderPageCompletesHandshakeAndProducesAFrame() throws Exception {
        assumeTrue(Boolean.getBoolean("mcwebui.directCef.integration"));
        String configuredUrl = System.getProperty("mcwebui.directCef.integration.url", "").trim();
        String configuredRuntime = System.getProperty("mcwebui.directCef.integration.runtimeDir", "").trim();
        String configuredInstance = System.getProperty("mcwebui.directCef.integration.instanceRoot", "").trim();
        assumeTrue(!configuredRuntime.isEmpty() || !configuredInstance.isEmpty(),
                "integration requires runtimeDir or instanceRoot");
        Path instanceRoot = configuredInstance.isEmpty()
                ? Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
                : Path.of(configuredInstance).toAbsolutePath().normalize();
        ValidatedDirectCefRuntime validatedRuntime = DirectCefRuntimeDiscovery.discover(instanceRoot,
                configuredRuntime.isEmpty() ? null : Path.of(configuredRuntime));
        String configuredCache = System.getProperty("mcwebui.directCef.integration.cacheDir", "").trim();
        Path cache = configuredCache.isEmpty()
                ? DirectCefRuntimeDiscovery.standardCacheDirectory(instanceRoot, validatedRuntime.identity())
                : Path.of(configuredCache).toAbsolutePath().normalize();
        Path frontendResources = Path.of(System.getProperty("mcwebui.directCef.integration.resources",
                "build/resources/main")).toAbsolutePath();
        assumeTrue(Files.isRegularFile(frontendResources.resolve("web/playground/index.html")),
                "processed frontend resources are missing: " + frontendResources);
        Files.createDirectories(cache);

        WebStateStore state = new WebStateStore();
        state.publish("demo.counter", 0);
        BridgeDispatcher dispatcher = new BridgeDispatcher().register("demo.ping",
                request -> Map.of("echo", request.payload().getOrDefault("message", "")));
        try (URLClassLoader frontendLoader = new URLClassLoader(
                     new java.net.URL[]{frontendResources.toUri().toURL()}, null)) {
            WebAppDefinition app = NeoForgeBuiltinApps.playgroundForLoader(frontendLoader, dispatcher);
            WebAppRegistry.process().register(app);
            try (BundledWebPageServer pageServer = configuredUrl.isEmpty()
                     ? BundledWebPageServer.start(app) : null;
             WebBridge bridge = new WebBridge(WebOrigin.mcui(NeoForgeWebAppRoutes.host(app.id())),
                     app.permissions(), app.bridge(), state);
             DirectCefRuntime runtime = DirectCefRuntime.create(validatedRuntime,
                     configuredUrl.isEmpty() ? pageServer.url().toString() : configuredUrl,
                     cache, 0L, 854, 480, 60)) {
            long navigationEpoch = runtime.bridgeNavigationEpoch();
            final long[] outboundEpoch = {navigationEpoch};
            try (DirectBridgeHost host = new DirectBridgeHost(bridge,
                    encoded -> runtime.deliverBridgeMessage(outboundEpoch[0], encoded))) {
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (!host.connected() && System.nanoTime() < deadline) {
                    long currentEpoch = runtime.bridgeNavigationEpoch();
                    if (currentEpoch != navigationEpoch) {
                        navigationEpoch = currentEpoch;
                        outboundEpoch[0] = currentEpoch;
                        host.resetSession();
                    }
                    for (int drained = 0; drained < 64; drained++) {
                        DirectCefRuntime.BridgeQuery query = runtime.pollBridgeQuery();
                        if (query == null) break;
                        assertTrue(runtime.completeBridgeQuery(query.id(), host.handle(query.request())));
                    }
                    runtime.requestFrame();
                    Thread.sleep(5);
                }
                assertTrue(host.connected(), () -> "Direct CEF page did not handshake: "
                        + runtime.diagnosticsJson());
                long paintDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                String diagnostics = runtime.diagnosticsJson();
                while (!diagnostics.matches(".*\"publishedGenerations\":[1-9][0-9]*.*")
                        && System.nanoTime() < paintDeadline) {
                    runtime.requestFrame();
                    Thread.sleep(5);
                    diagnostics = runtime.diagnosticsJson();
                }
                assertTrue(diagnostics.contains("\"bridgeBootstrapInstalled\":true"), diagnostics);
                assertTrue(diagnostics.matches(".*\"bridgeHandshakesCompleted\":[1-9][0-9]*.*"), diagnostics);
                assertTrue(diagnostics.matches(".*\"publishedGenerations\":[1-9][0-9]*.*"), diagnostics);
            }
            } finally {
                WebAppRegistry.process().clear();
            }
        }
    }

    private static String required(String name) {
        String value = System.getProperty(name, "").trim();
        if (value.isEmpty()) throw new IllegalStateException("Missing integration property: " + name);
        return value;
    }
}
