package dev.qingmo.mcwebui.target.neoforge1211;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.qingmo.mcwebui.api.WebAppDefinition;
import dev.qingmo.mcwebui.bridge.BridgeDispatcher;
import dev.qingmo.mcwebui.resource.WebResourceLocation;
import dev.qingmo.mcwebui.resource.WebResourceRequest;
import dev.qingmo.mcwebui.resource.WebResourceResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Serves one registered WebApp without making the Direct CEF runtime depend on
 * a separately launched development HTTP server.
 *
 * <p>The server binds only to IPv4 loopback and uses a fresh, unguessable path
 * for each process-owned instance.  The path is deliberately part of the
 * returned URL: Direct CEF's bridge trust check can therefore continue to use
 * its exact URL key rather than trusting every page on localhost.</p>
 */
final class BundledWebPageServer implements AutoCloseable {
    private static final String TOKEN_ROOT = "/__mcwebui/";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 24;

    private final HttpServer server;
    private final ExecutorService executor;
    private final String tokenRoot;
    private final URI baseUri;
    private volatile boolean closed;

    private final WebAppDefinition app;

    private BundledWebPageServer(HttpServer server, ExecutorService executor, String tokenRoot,
                                 WebAppDefinition app) {
        this.server = Objects.requireNonNull(server, "server");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.tokenRoot = Objects.requireNonNull(tokenRoot, "tokenRoot");
        this.app = Objects.requireNonNull(app, "app");
        InetSocketAddress address = server.getAddress();
        this.baseUri = URI.create("http://127.0.0.1:" + address.getPort() + tokenRoot);
    }

    /** Starts an isolated server on an ephemeral loopback port. */
    static BundledWebPageServer start() {
        return start(NeoForgeBuiltinApps.playgroundForLoader(
                BundledWebPageServer.class.getClassLoader(), new BridgeDispatcher()));
    }

    static BundledWebPageServer start(ClassLoader resourceLoader) {
        return start(NeoForgeBuiltinApps.playgroundForLoader(resourceLoader, new BridgeDispatcher()));
    }

    static BundledWebPageServer start(WebAppDefinition app) {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomToken());
        String tokenRoot = TOKEN_ROOT + token + "/";
        ExecutorService executor = Executors.newFixedThreadPool(2, new ServerThreadFactory());
        HttpServer server = null;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            BundledWebPageServer pageServer = new BundledWebPageServer(server, executor, tokenRoot, app);
            server.createContext("/", pageServer::handle);
            server.setExecutor(executor);
            server.start();
            return pageServer;
        } catch (IOException | RuntimeException failure) {
            if (server != null) server.stop(0);
            executor.shutdownNow();
            throw new IllegalStateException("Unable to start the bundled MCWebUI page server", failure);
        }
    }

    URI url() {
        return baseUri;
    }

    int port() {
        return baseUri.getPort();
    }

    String tokenRoot() {
        return tokenRoot;
    }

    private static byte[] randomToken() {
        byte[] token = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(token);
        return token;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                Headers headers = exchange.getResponseHeaders();
                headers.set("Allow", "GET, HEAD");
                send(exchange, 405, "Method Not Allowed".getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
                return;
            }

            Resource resource = resource(exchange.getRequestURI());
            if (resource == null) {
                send(exchange, 404, "Not Found".getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
                return;
            }
            if ("HEAD".equalsIgnoreCase(method)) {
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", resource.mime());
                headers.set("Cache-Control", "no-store");
                headers.set("Content-Length", Integer.toString(resource.body().length));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            send(exchange, 200, resource.body(), resource.mime());
        }
    }

    private Resource resource(URI requestUri) {
        String rawPath = requestUri == null ? null : requestUri.getRawPath();
        if (rawPath == null || !rawPath.startsWith(tokenRoot)) return null;
        // Decode once only after checking the route prefix.  Re-encoded slash,
        // backslash, dot and NUL bytes are rejected below as path components.
        final String path;
        try {
            path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEncoding) {
            return null;
        }
        if (!path.startsWith(tokenRoot) || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0) return null;
        String relative = path.substring(tokenRoot.length());
        if (relative.isEmpty()) relative = app.entry();
        if (relative.startsWith("/") || relative.endsWith("/")) return null;
        String[] components = relative.split("/", -1);
        for (String component : components) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)) return null;
        }
        try {
            WebResourceLocation location = new WebResourceLocation(app.id().namespace(),
                    "/" + app.id().path() + "/" + relative);
            WebResourceResponse response = app.resources().resolve(
                    new WebResourceRequest(location, "GET", Map.of()));
            if (response == null || response.status() != 200) return null;
            return new Resource(response.body(), response.mimeType());
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static void send(HttpExchange exchange, int status, byte[] body, String mime) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", mime);
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        server.stop(0);
        executor.shutdownNow();
    }

    private record Resource(byte[] body, String mime) { }

    private static final class ServerThreadFactory implements ThreadFactory {
        private int nextId;

        @Override
        public synchronized Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "mcwebui-page-server-" + (++nextId));
            thread.setDaemon(true);
            return thread;
        }
    }
}
