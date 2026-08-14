package dev.qingmo.mcwebui.target.neoforge1211;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BundledWebPageServerTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void servesBundledPageFromRandomLoopbackPath() throws Exception {
        try (BundledWebPageServer first = BundledWebPageServer.start();
             BundledWebPageServer second = BundledWebPageServer.start()) {
            assertEquals("http", first.url().getScheme());
            assertEquals("127.0.0.1", first.url().getHost());
            assertTrue(first.port() > 0);
            assertTrue(first.tokenRoot().matches("/__mcwebui/[A-Za-z0-9_-]{32,}/"));
            assertNotEquals(first.url().getPath(), second.url().getPath());

            HttpResponse<String> root = get(first.url(), "");
            assertEquals(200, root.statusCode());
            assertTrue(root.headers().firstValue("content-type").orElse("").startsWith("text/html"));
            assertTrue(root.body().contains("<html"), root.body());

            Matcher scriptPath = Pattern.compile("(?:src|href)=\"(assets/[^\"]+\\.js)\"").matcher(root.body());
            assertTrue(scriptPath.find(), "bundled page should reference a JavaScript asset");
            HttpResponse<String> script = get(first.url(), scriptPath.group(1) + "?cache=ignored");
            assertEquals(200, script.statusCode());
            assertTrue(script.headers().firstValue("content-type").orElse("").startsWith("text/javascript"));
            assertFalse(script.body().isEmpty());

            HttpResponse<Void> head = request(first.url(), "", "HEAD", HttpResponse.BodyHandlers.discarding());
            assertEquals(200, head.statusCode());
            assertTrue(head.headers().firstValue("content-length").isPresent());
        }
    }

    @Test
    void rejectsOtherPathsMethodsAndTraversal() throws Exception {
        URI closedUrl;
        try (BundledWebPageServer server = BundledWebPageServer.start()) {
            closedUrl = server.url();
            assertEquals(404, get(URI.create("http://127.0.0.1:" + server.port() + "/"), "").statusCode());
            assertEquals(404, get(URI.create("http://127.0.0.1:" + server.port() + server.tokenRoot() + "../"), "").statusCode());
            assertEquals(404, get(URI.create("http://127.0.0.1:" + server.port() + server.tokenRoot() + "%2e%2e/"), "").statusCode());

            HttpResponse<String> post = request(server.url(), "", "POST", HttpResponse.BodyHandlers.ofString());
            assertEquals(405, post.statusCode());
            assertEquals("GET, HEAD", post.headers().firstValue("allow").orElse(""));
        }
        URI finalClosedUrl = closedUrl;
        assertThrows(Exception.class, () -> get(finalClosedUrl, ""),
                "closing the page server must release its listener");
    }

    private static HttpResponse<String> get(URI base, String suffix) throws Exception {
        return request(base, suffix, "GET", HttpResponse.BodyHandlers.ofString());
    }

    private static <T> HttpResponse<T> request(URI base, String suffix, String method,
                                                 HttpResponse.BodyHandler<T> handler) throws Exception {
        URI uri = URI.create(base.toString() + suffix);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return CLIENT.send(request, handler);
    }
}
