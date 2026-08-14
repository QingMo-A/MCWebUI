package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.backend.BrowserBackend;
import dev.qingmo.mcwebui.api.WebAppDefinition;
import dev.qingmo.mcwebui.api.WebViewportPolicy;
import dev.qingmo.mcwebui.bridge.BridgeDispatcher;
import dev.qingmo.mcwebui.input.WebFocusEvent;
import dev.qingmo.mcwebui.input.WebKeyEvent;
import dev.qingmo.mcwebui.input.WebMouseEvent;
import dev.qingmo.mcwebui.input.WebScrollEvent;
import dev.qingmo.mcwebui.input.WebTextInputEvent;
import dev.qingmo.mcwebui.runtime.DefaultWebRuntime;
import dev.qingmo.mcwebui.runtime.WebRuntime;
import dev.qingmo.mcwebui.runtime.WebView;
import dev.qingmo.mcwebui.runtime.WebViewConfig;
import dev.qingmo.mcwebui.security.WebOrigin;
import dev.qingmo.mcwebui.nativecef.DirectCefRuntimeDiscovery;
import dev.qingmo.mcwebui.nativecef.ValidatedDirectCefRuntime;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Single NeoForge screen-owned composition of common view, MCEF surface and bridge session. */
final class NeoForgeWebSession implements AutoCloseable {
    private final WebAppDefinition app;
    private final WebRuntime runtime;
    private final BrowserBackend backend;
    private final boolean directBackend;
    private final ExternalFramePacer directFramePacer;
    private BundledWebPageServer bundledPageServer;
    private String backendName = "mcef";
    private final NeoForgeDemoBridge demo;
    private WebView view;
    private NeoForgeRenderableSurface surface;
    private double guiScale = 1.0;
    private int minecraftGuiWidth;
    private int minecraftGuiHeight;
    private int browserViewportWidth;
    private int browserViewportHeight;
    private boolean followGuiSize = true;
    private boolean initialized;
    private volatile boolean closed;
    private volatile boolean visible;
    private boolean prewarming;
    private long prewarmDeadlineNanos;

    NeoForgeWebSession(BridgeDispatcher dispatcher, NeoForgeDemoBridge demo) {
        this(NeoForgeBuiltinApps.playground(Objects.requireNonNull(dispatcher, "dispatcher")),
                Objects.requireNonNull(demo, "demo"));
    }

    NeoForgeWebSession(WebAppDefinition app) {
        this(app, null);
    }

    private NeoForgeWebSession(WebAppDefinition app, NeoForgeDemoBridge demo) {
        this.app = Objects.requireNonNull(app, "app");
        this.demo = demo;
        this.runtime = new DefaultWebRuntime(app.permissions(), app.bridge());
        this.backend = createBackend();
        this.directBackend = backend instanceof DirectCefBackend;
        this.directFramePacer = directBackend
                ? new ExternalFramePacer(((DirectCefBackend) backend).targetHz()) : null;
    }

    private BrowserBackend createBackend() {
        String selected = normalizeBackendSelection(System.getProperty("mcwebui.browserBackend", "mcef"));
        backendName = selected.isEmpty() ? "mcef" : selected;
        if (selected.isEmpty() || selected.equals("mcef")) return new NeoForgeMcefBackend();
        if (!selected.equals("direct-cef")) throw new IllegalStateException("Unknown MCWebUI browser backend: " + selected);
        // The URL override belongs to the built-in proof runner. Consumer apps always
        // use their own isolated capability URL and provider-backed loopback server.
        String url = demo == null ? "" : System.getProperty("mcwebui.directCef.url", "").trim();
        String runtimeOverride = System.getProperty(DirectCefRuntimeDiscovery.RUNTIME_OVERRIDE_PROPERTY, "").trim();
        String cacheOverride = System.getProperty(DirectCefRuntimeDiscovery.CACHE_OVERRIDE_PROPERTY, "").trim();
        java.nio.file.Path instanceRoot = directCefInstanceRoot();
        ValidatedDirectCefRuntime validatedRuntime = DirectCefRuntimeDiscovery.discover(instanceRoot,
                runtimeOverride.isEmpty() ? null : java.nio.file.Path.of(runtimeOverride));
        java.nio.file.Path cache = cacheOverride.isEmpty()
                ? DirectCefRuntimeDiscovery.standardCacheDirectory(instanceRoot, validatedRuntime.identity())
                : java.nio.file.Path.of(cacheOverride).toAbsolutePath().normalize();
        if (url.isEmpty()) {
            // Validate all static native settings before opening a listening socket.  The
            // normal validator intentionally still rejects an empty user-supplied URL.
            validateDirectBackendConfiguration(System.getProperty("os.name", ""),
                    "http://127.0.0.1:1/", validatedRuntime);
            try {
                bundledPageServer = BundledWebPageServer.start(app);
                url = bundledPageServer.url().toString();
                System.out.println("[MCWebUI] Direct CEF bundled page server started on 127.0.0.1:"
                        + bundledPageServer.port() + " (capability path redacted)");
            } catch (RuntimeException failure) {
                if (bundledPageServer != null) bundledPageServer.close();
                bundledPageServer = null;
                throw failure;
            }
        } else {
            validateDirectBackendConfiguration(System.getProperty("os.name", ""), url, validatedRuntime);
        }
        long parent = minecraftWindowHandle();
        try {
            return new DirectCefBackend(parent, validatedRuntime, cache, url,
                    Integer.getInteger("mcwebui.directCef.targetHz", 60));
        } catch (RuntimeException failure) {
            if (bundledPageServer != null) {
                bundledPageServer.close();
                bundledPageServer = null;
            }
            throw failure;
        }
    }

    static java.nio.file.Path directCefInstanceRoot() {
        String configured = System.getProperty(DirectCefRuntimeDiscovery.INSTANCE_ROOT_PROPERTY, "").trim();
        if (!configured.isEmpty()) return java.nio.file.Path.of(configured).toAbsolutePath().normalize();
        try {
            return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Unable to determine the Minecraft game directory for Direct CEF", ex);
        }
    }

    static String normalizeBackendSelection(String value) {
        return value == null ? "mcef" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    static void validateDirectEnvironment(String osName, String url) {
        if (osName == null || !osName.toLowerCase(java.util.Locale.ROOT).contains("win"))
            throw new IllegalStateException("Direct CEF backend requires Windows");
        if (url == null || url.trim().isEmpty())
            throw new IllegalStateException("mcwebui.directCef.url is required for direct-cef");
        validateDirectBridgeUrl(url);
    }

    static void validateDirectBackendConfiguration(String osName, String url,
                                                   ValidatedDirectCefRuntime runtime) {
        validateDirectEnvironment(osName, url);
        if (runtime == null) throw new IllegalStateException("Direct CEF validated runtime is required");
    }

    static void validateDirectBridgeUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value.trim());
            String host = uri.getHost();
            boolean loopback = host != null && (host.equalsIgnoreCase("localhost")
                    || host.equals("127.0.0.1") || host.equals("::1"));
            if (!"http".equalsIgnoreCase(uri.getScheme()) || !loopback || uri.getRawUserInfo() != null) {
                throw new IllegalStateException("Direct CEF bridge URL must be an HTTP loopback origin");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("mcwebui.directCef.url is invalid", ex);
        }
    }

    private static long minecraftWindowHandle() {
        return Minecraft.getInstance().getWindow().getWindow();
    }

    void init(int guiWidth, int guiHeight, double guiScale) {
        initialize(guiWidth, guiHeight, guiScale, true);
    }

    void warmUp(int guiWidth, int guiHeight, double guiScale) {
        initialize(guiWidth, guiHeight, guiScale, false);
    }

    private void initialize(int guiWidth, int guiHeight, double guiScale, boolean activate) {
        if (closed) throw new IllegalStateException("Session is closed");
        if (initialized) {
            resize(guiWidth, guiHeight, guiScale);
            if (activate) activate();
            return;
        }
        this.guiScale = requireScale(guiScale);
        this.minecraftGuiWidth = requireDimension(guiWidth, "guiWidth");
        this.minecraftGuiHeight = requireDimension(guiHeight, "guiHeight");
        this.followGuiSize = demo != null
                ? NeoForgeMod.CLIENT_CONFIG.followGuiSize()
                : app.options().viewportPolicy() == WebViewportPolicy.GUI;
        // The default GUI mode uses Screen's logical coordinates directly. In framebuffer mode,
        // scale the browser back to physical-equivalent pixels so GUI scale 1 and 2 keep the
        // same CSS density in a fixed-size window; resize updates that physical viewport.
        int width = browserDimension(guiWidth, this.guiScale, this.followGuiSize);
        int height = browserDimension(guiHeight, this.guiScale, this.followGuiSize);
        this.browserViewportWidth = width;
        this.browserViewportHeight = height;
        try {
            String appHost = NeoForgeWebAppRoutes.host(app.id());
            view = runtime.createView(new WebViewConfig(WebOrigin.mcui(appHost),
                    "/" + app.entry(), width, height));
            view.initialize();
            app.bridgeInitializer().accept(view.bridge());
            surface = (NeoForgeRenderableSurface) backend.createSurface(view.config(), view.bridge());
            view.setVisible(activate);
            visible = activate;
            view.focus(activate);
            // Direct CEF was created at this exact extent. Avoid an immediate redundant
            // WasResized callback, which would otherwise invalidate the mailbox while the
            // hidden prewarm is waiting for its first texture.
            if (!directBackend) surface.resize(width, height);
            surface.load(NeoForgeWebAppRoutes.entryUrl(app));
            // A Direct CEF warm session remains browser-visible (but is not drawn or
            // focused) just long enough to create its first accelerated texture. Hiding
            // it immediately would make CEF suppress the exact paint we are prewarming.
            prewarming = directBackend && !activate;
            prewarmDeadlineNanos = prewarming ? System.nanoTime() + 10_000_000_000L : 0L;
            setSurfaceVisible(activate || prewarming);
            if (demo != null) {
                demo.setDiagnosticsSupplier(this::diagnostics);
                demo.publishCounter(view.bridge());
            }
            initialized = true;
        } catch (RuntimeException ex) {
            close();
            throw ex;
        }
    }

    void activate() {
        if (closed || !initialized || view == null) return;
        prewarming = false;
        if (directFramePacer != null) directFramePacer.reset();
        view.setVisible(true);
        visible = true;
        setSurfaceVisible(true);
        focus(true);
    }

    void deactivate() {
        if (closed || !initialized || view == null || (!visible && !prewarming)) return;
        focus(false);
        setSurfaceVisible(false);
        view.setVisible(false);
        visible = false;
        prewarming = false;
        if (directFramePacer != null) directFramePacer.reset();
        logDirectRuntimeEvidence("hidden");
    }

    boolean hasRenderableFrame() {
        return !closed && surface != null && surface.textureId() > 0;
    }

    /** Advances hidden Direct CEF initialization from the Minecraft client/render thread. */
    boolean prewarmTick(long frameTimeNanos) {
        if (closed || !prewarming || !(surface instanceof DirectCefRenderableSurface direct)) return false;
        if (frameTimeNanos >= prewarmDeadlineNanos) {
            finishPrewarm();
            return false;
        }
        if (directFramePacer.shouldRequest(frameTimeNanos)) surface.requestExternalFrame();
        boolean locked = direct.beginRenderFrame();
        if (locked) {
            boolean frameReady;
            try {
                // Do not hide CEF on its initial blank texture. Waiting for the Java
                // handshake also proves the real Vue bundle has loaded and executed.
                frameReady = surface.textureId() > 0 && direct.bridgeReady();
            } finally {
                direct.endRenderFrame();
            }
            if (frameReady) {
                finishPrewarm();
                return true;
            }
        }
        return false;
    }

    boolean isPrewarming() { return prewarming; }

    private void finishPrewarm() {
        prewarming = false;
        setSurfaceVisible(false);
        if (directFramePacer != null) directFramePacer.reset();
        logDirectRuntimeEvidence("prewarm-complete");
    }

    void resize(int guiWidth, int guiHeight, double guiScale) {
        if (closed || view == null || surface == null) return;
        // GLFW may report a transient zero extent while the window is minimized
        // or its fullscreen swapchain is being replaced. Preserve the last
        // valid browser surface and wait for the next positive resize callback.
        if (guiWidth < 1 || guiHeight < 1 || !Double.isFinite(guiScale) || guiScale <= 0) return;
        this.guiScale = requireScale(guiScale);
        this.minecraftGuiWidth = requireDimension(guiWidth, "guiWidth");
        this.minecraftGuiHeight = requireDimension(guiHeight, "guiHeight");
        int width = browserDimension(guiWidth, this.guiScale, this.followGuiSize);
        int height = browserDimension(guiHeight, this.guiScale, this.followGuiSize);
        if (width == browserViewportWidth && height == browserViewportHeight) {
            // A GLFW fullscreen transition can replace the current WGL context even when
            // Minecraft's logical GUI dimensions remain unchanged. Let Direct CEF observe
            // every Screen resize so its native render path can rebind safely.
            if (directBackend) surface.resize(width, height);
            return;
        }
        this.browserViewportWidth = width;
        this.browserViewportHeight = height;
        view.resize(width, height);
        surface.resize(width, height);
    }

    void refreshRenderContext() {
        if (surface instanceof DirectCefRenderableSurface direct) direct.refreshGlContext();
    }

    void mouseMove(double x, double y) { input(new WebMouseEvent(WebMouseEvent.Type.MOVE, browserX(x), browserY(y), -1)); }
    void mouseButton(double x, double y, int button, boolean down) {
        input(new WebMouseEvent(down ? WebMouseEvent.Type.DOWN : WebMouseEvent.Type.UP, browserX(x), browserY(y), button));
    }
    void mouseScroll(double x, double y, double deltaX, double deltaY) {
        double browserDeltaX = directBackend ? cefWheelDelta(deltaX) : deltaX;
        double browserDeltaY = directBackend ? cefWheelDelta(deltaY) : deltaY;
        input(new WebScrollEvent(browserX(x), browserY(y), browserDeltaX, browserDeltaY));
    }
    void key(int keyCode, int scanCode, int modifiers, boolean down) {
        input(new WebKeyEvent(down ? WebKeyEvent.Type.DOWN : WebKeyEvent.Type.UP, keyCode, scanCode, modifiers));
    }
    void text(String text, boolean composition, boolean committed) { input(new WebTextInputEvent(text, composition, committed)); }
    void focus(boolean focused) {
        if (view != null) view.focus(focused);
        input(new WebFocusEvent(focused));
    }

    NeoForgeRenderableSurface surface() { return surface; }
    WebAppDefinition app() { return app; }
    boolean isDirectBackend() { return directBackend; }
    void beginFrame(long frameTimeNanos) {
        if (shouldBeginFrame(closed, visible, view == null ? null : view.state().lifecycle(), surface != null)) {
            surface.metrics().recordGameSignal();
            if (directFramePacer == null || directFramePacer.shouldRequest(frameTimeNanos)) {
                surface.requestExternalFrame();
            }
        }
    }
    boolean isClosed() { return closed; }
    void pumpBridge() {
        if (!closed && surface instanceof DirectCefRenderableSurface direct) direct.pumpBridge();
    }

    private void input(dev.qingmo.mcwebui.input.WebInputEvent event) {
        if (!closed && surface != null) surface.input(event);
    }

    private void setSurfaceVisible(boolean visible) {
        if (surface instanceof DirectCefRenderableSurface direct) direct.setVisible(visible);
    }

    private void logDirectRuntimeEvidence(String checkpoint) {
        if (surface instanceof DirectCefRenderableSurface direct) {
            System.out.println("[MCWebUI] Direct CEF runtime evidence checkpoint=" + checkpoint
                    + " diagnostics=" + direct.runtimeDiagnosticsJson());
        }
    }

    private Map<String, Object> diagnostics() {
        LinkedHashMap<String, Object> info = new LinkedHashMap<>();
        info.put("targetId", "neoforge-1.21.1");
        info.put("webAppId", app.id().toString());
        info.put("loader", "NeoForge");
        info.put("minecraftVersion", "1.21.1");
        info.put("javaVersion", 21);
        info.put("browserBackend", directBackend ? "Direct CEF (experimental)" : "CinemaMod MCEF");
        info.put("browserBackendSelection", backendName);
        info.put("browserVersion", directBackend ? "144 (proof runtime)" : "2.1.6-1.21.1");
        if (backend instanceof DirectCefBackend direct) {
            var identity = direct.validatedRuntime().identity();
            info.put("directCefRuntimeId", identity.runtimeId());
            info.put("directCefRuntimeAbi", identity.mcwebuiRuntimeAbi());
            info.put("directCefPlatform", identity.platform());
            info.put("directCefArch", identity.arch());
            info.put("directCefRuntimeSource", direct.validatedRuntime().source().name());
            info.put("directCefRuntimeValidated", true);
        }
        info.put("minecraftGuiWidth", minecraftGuiWidth);
        info.put("minecraftGuiHeight", minecraftGuiHeight);
        info.put("browserViewportWidth", surface == null ? 0 : surface.width());
        info.put("browserViewportHeight", surface == null ? 0 : surface.height());
        info.put("followGuiSize", followGuiSize);
        info.put("viewportMode", followGuiSize ? "GUI" : "FRAMEBUFFER");
        info.put("externalFramePacing", surface != null && surface.supportsExternalFrames());
        info.put("configuredExternalFrameRateHz", directFramePacer == null ? 0 : directFramePacer.targetHz());
        boolean externalPacing = surface != null && surface.supportsExternalFrames();
        info.put("frameMode", externalPacing ? "GAME_SYNC" : "BACKEND_DEFAULT");
        info.put("framePacingCapability", externalPacing ? "EXTERNAL_BEGIN_FRAME" : "UNSUPPORTED");
        info.put("proofRuntime", externalPacing ? "PATCHED" : "STOCK");
        info.put("guiScale", guiScale);
        info.put("paintCallbacks", surface == null ? 0L : surface.metrics().paintCallbacks());
        info.put("framebufferWidth", surface == null ? 0 : surface.metrics().width());
        info.put("framebufferHeight", surface == null ? 0 : surface.metrics().height());
        info.put("estimatedPaintBytes", surface == null ? 0L : surface.metrics().estimatedPaintBytes());
        info.put("paintRateHz", surface == null ? 0.0 : surface.metrics().paintRateHz());
        info.put("gameSignals", surface == null ? 0L : surface.metrics().gameSignals());
        info.put("externalFrameRequests", surface == null ? 0L : surface.metrics().externalRequests());
        info.put("gameSignalsPerSecond", surface == null ? 0.0 : surface.metrics().gameSignalRateHz());
        info.put("externalBeginFramesPerSecond", surface == null ? 0.0 : surface.metrics().externalRequestRateHz());
        info.put("viewState", view == null ? "CLOSED" : view.state().lifecycle().name());
        info.put("sessionState", closed ? "CLOSED" : "ACTIVE");
        return Map.copyOf(info);
    }

    private double browserX(double guiCoordinate) { return mapCoordinate(guiCoordinate, minecraftGuiWidth, browserViewportWidth); }
    private double browserY(double guiCoordinate) { return mapCoordinate(guiCoordinate, minecraftGuiHeight, browserViewportHeight); }

    // Keep these conversions package-visible so the coordinate-space contract can be tested
    // without constructing a live CEF/Minecraft session. The scale is validated by init/resize.
    static int browserDimension(int guiSize, double guiScale, boolean followGuiSize) {
        if (guiSize < 1) throw new IllegalArgumentException("guiSize must be positive");
        if (followGuiSize) return guiSize;
        double scaled = guiSize * guiScale;
        if (!Double.isFinite(scaled)) return Integer.MAX_VALUE;
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, Math.round(scaled)));
    }
    static double mapCoordinate(double guiCoordinate, int guiSize, int browserSize) {
        if (guiSize < 1 || browserSize < 1) throw new IllegalArgumentException("coordinate spaces must be positive");
        return guiCoordinate * browserSize / (double) guiSize;
    }
    static double cefWheelDelta(double minecraftDelta) {
        if (!Double.isFinite(minecraftDelta)) throw new IllegalArgumentException("wheel delta must be finite");
        return minecraftDelta * 120.0;
    }
    static boolean shouldBeginFrame(boolean closed, boolean visible,
                                    dev.qingmo.mcwebui.runtime.WebViewLifecycle lifecycle,
                                    boolean surfacePresent) {
        return !closed && visible && surfacePresent
                && lifecycle == dev.qingmo.mcwebui.runtime.WebViewLifecycle.VISIBLE;
    }
    private static int requireDimension(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
    private static double requireScale(double value) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("guiScale must be positive");
        return value;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (view != null) view.setVisible(false);
        visible = false;
        prewarming = false;
        logDirectRuntimeEvidence("shutdown");
        if (surface != null) surface.close();
        if (view != null) {
            if (demo != null) demo.removeBridge(view.bridge());
            view.close();
        }
        runtime.close();
        backend.close();
        if (bundledPageServer != null) {
            bundledPageServer.close();
            bundledPageServer = null;
        }
    }
}
