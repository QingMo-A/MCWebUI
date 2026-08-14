package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.api.WebAppDefinition;
import dev.qingmo.mcwebui.api.WebAppRegistry;
import dev.qingmo.mcwebui.resource.ClasspathWebResourceProvider;
import org.cef.callback.CefCallback;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.RecordingCefResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class RegisteredWebAppResourcesTest {
    @AfterEach void clearRegistry() { WebAppRegistry.process().clear(); }

    @Test void sameProviderSemanticsReachDirectAndMcefWithoutTraversal() throws Exception {
        WebAppDefinition app = WebAppDefinition.builder("consumer:sample")
                .resources(new ClasspathWebResourceProvider(getClass().getClassLoader(), "web"))
                .entry("index.html")
                .build();
        WebAppRegistry.process().register(app);

        try (BundledWebPageServer server = BundledWebPageServer.start(app)) {
            assertTrue(readHttp(server.url().resolve("index.html")).contains("consumer:sample"));
            assertTrue(readHttp(server.url().resolve("app.js")).contains("consumerBridgeState"));
            assertTrue(readHttp(server.url().resolve("app.css")).contains("consumer-app"));
            assertEquals(404, status(server.url().resolve("%2e%2e/secret")));
            assertEquals(404, status(URI.create("http://127.0.0.1:" + server.port() + "/index.html")));
        }

        String host = NeoForgeWebAppRoutes.host(app.id());
        Response mcef = readMcef("mcui://" + host + "/index.html");
        assertEquals(200, mcef.status());
        assertTrue(mcef.body().contains("consumer:sample"));
        assertEquals(404, readMcef("mcui://" + host + "/../secret").status());
        assertEquals(404, readMcef("mcui://external.example/index.html").status());
    }

    private static String readHttp(URI uri) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        assertEquals(200, connection.getResponseCode());
        return new String(connection.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int status(URI uri) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        return connection.getResponseCode();
    }

    private static Response readMcef(String url) {
        McuiSchemeHandler handler = new McuiSchemeHandler(url);
        CefCallback callback = new CefCallback() {
            @Override public void Continue() { }
            @Override public void cancel() { }
        };
        assertTrue(handler.processRequest(null, callback));
        RecordingCefResponse response = new RecordingCefResponse();
        IntRef length = new IntRef();
        handler.getResponseHeaders(response, length, new StringRef());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32];
        while (true) {
            IntRef read = new IntRef();
            boolean more = handler.readResponse(buffer, buffer.length, read, callback);
            if (read.get() > 0) output.write(buffer, 0, read.get());
            if (!more) break;
        }
        return new Response(response.status(), output.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private record Response(int status, String body) { }
}
