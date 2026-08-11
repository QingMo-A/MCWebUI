package dev.qingmo.mcwebui.target.neoforge1211;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import dev.qingmo.mcwebui.backend.BrowserBackend;
import dev.qingmo.mcwebui.backend.BrowserSurface;
import dev.qingmo.mcwebui.backend.FrameMetrics;
import dev.qingmo.mcwebui.bridge.BridgeCapability;
import dev.qingmo.mcwebui.bridge.BridgeCodec;
import dev.qingmo.mcwebui.bridge.BridgeError;
import dev.qingmo.mcwebui.bridge.BridgeHandshake;
import dev.qingmo.mcwebui.bridge.BridgeMessage;
import dev.qingmo.mcwebui.bridge.BridgeRequest;
import dev.qingmo.mcwebui.bridge.BridgeResponse;
import dev.qingmo.mcwebui.bridge.BridgeStateUpdate;
import dev.qingmo.mcwebui.bridge.BridgeSubscribe;
import dev.qingmo.mcwebui.bridge.BridgeUnsubscribe;
import dev.qingmo.mcwebui.bridge.WebBridge;
import dev.qingmo.mcwebui.input.WebFocusEvent;
import dev.qingmo.mcwebui.input.WebInputEvent;
import dev.qingmo.mcwebui.input.WebKeyEvent;
import dev.qingmo.mcwebui.input.WebMouseEvent;
import dev.qingmo.mcwebui.input.WebScrollEvent;
import dev.qingmo.mcwebui.input.WebTextInputEvent;
import dev.qingmo.mcwebui.runtime.WebViewConfig;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefMessageRouterHandlerAdapter;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * CinemaMod MCEF 2.1.6-1.21.1 adapter. The browser surface keeps MCEF's native OpenGL paint path;
 * the message router is the actual CefQuery JSON transport used by the shared frontend bridge.
 */
public final class NeoForgeMcefBackend implements BrowserBackend {
    private static final String ARTIFACT = "com.cinemamod:mcef-neoforge:2.1.6-1.21.1";
    private static final ConcurrentMap<CefBrowser, McefSurface> SURFACES = new ConcurrentHashMap<>();
    private static final Object HOOK_LOCK = new Object();
    private static volatile CefMessageRouter MESSAGE_ROUTER;
    private static volatile boolean LOAD_HOOK_INSTALLED;

    /** Install the official JCEF message router and one load hook after MCEF initialization. */
    public static void installRuntimeHooks() {
        if (!MCEF.isInitialized()) throw new IllegalStateException("MCEF is not initialized");
        if (MESSAGE_ROUTER != null && LOAD_HOOK_INSTALLED) return;
        synchronized (HOOK_LOCK) {
            if (MESSAGE_ROUTER == null) {
                CefClient client = MCEF.getClient().getHandle();
                MESSAGE_ROUTER = CefMessageRouter.create(new RouterHandler());
                client.addMessageRouter(MESSAGE_ROUTER);
            }
            if (!LOAD_HOOK_INSTALLED) {
                MCEF.getClient().addLoadHandler(new LoadHandler());
                LOAD_HOOK_INSTALLED = true;
            }
        }
    }

    @Override
    public NeoForgeRenderableSurface createSurface(WebViewConfig config, WebBridge bridge) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(bridge, "bridge");
        if (!MCEF.isInitialized()) throw new IllegalStateException("MCEF is not initialized");
        installRuntimeHooks();
        String url = config.origin().asUri() + config.initialPath();
        return new McefSurface(url, config, bridge);
    }

    public static String artifact() { return ARTIFACT; }

    private static final class McefSurface implements NeoForgeRenderableSurface {
        private final WebViewConfig config;
        private final FrameMetrics metrics = new FrameMetrics();
        private final BridgeHost host;
        private final InstrumentedMcefBrowser browser;
        private volatile int width;
        private volatile int height;
        private volatile boolean closed;

        private McefSurface(String url, WebViewConfig config, WebBridge bridge) {
            this.config = config;
            this.width = config.width();
            this.height = config.height();
            this.host = new BridgeHost(bridge);
            this.browser = new InstrumentedMcefBrowser(url, true, metrics);
            SURFACES.put(browser, this);
            this.browser.resize(width, height);
        }

        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public void load(String url) { ensureOpen(); browser.loadURL(url); }
        @Override public void resize(int width, int height) {
            if (width < 1 || height < 1) throw new IllegalArgumentException("surface dimensions must be positive");
            ensureOpen();
            this.width = width;
            this.height = height;
            browser.resize(width, height);
        }
        @Override public void input(WebInputEvent event) {
            ensureOpen();
            Objects.requireNonNull(event, "event");
            if (event instanceof WebMouseEvent mouse) {
                switch (mouse.type()) {
                    case MOVE -> browser.sendMouseMove((int) Math.round(mouse.x()), (int) Math.round(mouse.y()));
                    case DOWN -> browser.sendMousePress((int) Math.round(mouse.x()), (int) Math.round(mouse.y()), mouse.button());
                    case UP -> browser.sendMouseRelease((int) Math.round(mouse.x()), (int) Math.round(mouse.y()), mouse.button());
                }
            } else if (event instanceof WebScrollEvent scroll) {
                browser.sendMouseWheel((int) Math.round(scroll.x()), (int) Math.round(scroll.y()), scroll.deltaY(), (int) Math.round(scroll.deltaX()));
            } else if (event instanceof WebKeyEvent key) {
                if (key.type() == WebKeyEvent.Type.DOWN) browser.sendKeyPress(key.keyCode(), key.scanCode(), key.modifiers());
                else browser.sendKeyRelease(key.keyCode(), key.scanCode(), key.modifiers());
            } else if (event instanceof WebTextInputEvent text) {
                // MCEF exposes committed UTF-16 key events. Composition is recorded by the target
                // input model, but full GLFW IME composition is a documented platform limitation.
                for (int i = 0; i < text.text().length(); i++) browser.sendKeyTyped(text.text().charAt(i), 0);
            } else if (event instanceof WebFocusEvent focus) {
                browser.setFocus(focus.focused());
            }
        }
        @Override public FrameMetrics metrics() { return metrics; }
        @Override public int textureId() { return browser.getRenderer().getTextureID(); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            host.close();
            SURFACES.remove(browser, this);
            browser.close();
        }

        private void onLoadStart(String url) {
            host.onNavigationStart(isTrustedUrl(url, config));
        }

        private void onLoadEnd(String url) {
            if (isTrustedUrl(url, config)) host.installBootstrap(browser, url);
            else host.onNavigationStart(false);
        }

        private void ensureOpen() { if (closed) throw new IllegalStateException("Browser surface is closed"); }
    }

    /** Target-local rendering capability; common BrowserSurface intentionally has no texture identity. */
    private static final class InstrumentedMcefBrowser extends MCEFBrowser {
        private final FrameMetrics metrics;
        private InstrumentedMcefBrowser(String url, boolean transparent, FrameMetrics metrics) {
            super(MCEF.getClient(), url, transparent);
            this.metrics = metrics;
            setCloseAllowed();
            createImmediately();
        }
        @Override
        public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
            super.onPaint(browser, popup, dirtyRects, buffer, width, height);
            if (!popup) {
                metrics.recordPaint(width, height);
            }
        }
    }

    private static final class RouterHandler extends CefMessageRouterHandlerAdapter {
        @Override
        public boolean onQuery(CefBrowser browser, org.cef.browser.CefFrame frame, long queryId,
                               String request, boolean persistent, CefQueryCallback callback) {
            McefSurface surface = SURFACES.get(browser);
            if (surface == null || !isTrustedUrl(browser.getURL(), surface.config)) {
                callback.failure(403, "MCWebUI bridge is only available on trusted mcui:// content");
                return true;
            }
            surface.host.handleQuery(browser, request, callback);
            return true;
        }
    }

    private static final class LoadHandler implements CefLoadHandler {
        @Override public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) { }
        @Override public void onLoadStart(CefBrowser browser, org.cef.browser.CefFrame frame, org.cef.network.CefRequest.TransitionType type) {
            McefSurface surface = SURFACES.get(browser);
            if (surface != null && frame.isMain()) surface.onLoadStart(frame.getURL());
        }
        @Override public void onLoadEnd(CefBrowser browser, org.cef.browser.CefFrame frame, int httpStatusCode) {
            McefSurface surface = SURFACES.get(browser);
            if (surface != null && frame.isMain()) surface.onLoadEnd(frame.getURL());
        }
        @Override public void onLoadError(CefBrowser browser, org.cef.browser.CefFrame frame,
                                          ErrorCode errorCode, String errorText, String failedUrl) { }
    }

    private static boolean isTrustedUrl(String value, WebViewConfig config) {
        if (value == null || config == null) return false;
        try {
            java.net.URI uri = java.net.URI.create(value);
            return "mcui".equalsIgnoreCase(uri.getScheme())
                    && config.origin().host().equalsIgnoreCase(uri.getHost())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (RuntimeException ex) { return false; }
    }

    /** One browser-side session. It translates CefQuery envelopes into common WebBridge operations. */
    private static final class BridgeHost implements AutoCloseable {
        private final WebBridge bridge;
        private final ConcurrentMap<String, dev.qingmo.mcwebui.state.WebStateSubscription> subscriptions = new ConcurrentHashMap<>();
        private final ArrayDeque<String> queuedMessages = new ArrayDeque<>();
        private final dev.qingmo.mcwebui.state.WebStateSubscription eventSubscription;
        private volatile CefBrowser browser;
        private volatile boolean bootstrapInstalled;
        private volatile boolean closed;

        private BridgeHost(WebBridge bridge) {
            this.bridge = bridge;
            this.eventSubscription = bridge.onEvent(event -> send(BridgeCodec.encode(event)));
        }

        private void handleQuery(CefBrowser browser, String request, CefQueryCallback callback) {
            if (closed) { callback.failure(410, "Bridge view is closed"); return; }
            this.browser = browser;
            BridgeMessage message;
            try { message = BridgeCodec.decode(request); }
            catch (RuntimeException ex) {
                callback.success(BridgeCodec.encode(BridgeResponse.failure("invalid", BridgeError.malformed("Invalid bridge envelope"))));
                return;
            }
            try {
                if (message instanceof BridgeHandshake) {
                    callback.success(BridgeCodec.encode(bridge.handshake()));
                } else if (message instanceof BridgeRequest rpc) {
                    callback.success(BridgeCodec.encode(bridge.request(rpc)));
                } else if (message instanceof BridgeSubscribe subscribe) {
                    bridge.requireBrowserCapability(BridgeCapability.STATE);
                    subscribe(subscribe.channel());
                    callback.success("{}");
                } else if (message instanceof BridgeUnsubscribe unsubscribe) {
                    unsubscribe(unsubscribe.channel());
                    callback.success("{}");
                } else {
                    callback.success(BridgeCodec.encode(BridgeResponse.failure("invalid",
                            BridgeError.malformed("Unsupported browser-to-host message"))));
                }
            } catch (SecurityException ex) {
                callback.success(BridgeCodec.encode(BridgeResponse.failure("invalid", BridgeError.denied(ex.getMessage()))));
            } catch (RuntimeException ex) {
                callback.success(BridgeCodec.encode(BridgeResponse.failure("invalid", BridgeError.internal("Bridge transport failed"))));
            }
        }

        private void subscribe(String channel) {
            subscriptions.computeIfAbsent(channel, key -> bridge.subscribeState(key,
                    update -> send(BridgeCodec.encode(update))));
        }

        private void unsubscribe(String channel) {
            var subscription = subscriptions.remove(channel);
            if (subscription != null) subscription.close();
        }

        private synchronized void installBootstrap(CefBrowser browser, String url) {
            if (closed) return;
            this.browser = browser;
            bootstrapInstalled = true;
            browser.executeJavaScript(bootstrapScript(), url, 0);
            while (!queuedMessages.isEmpty()) deliverNow(queuedMessages.removeFirst());
        }

        private synchronized void onNavigationStart(boolean trusted) {
            bootstrapInstalled = false;
            subscriptions.values().forEach(dev.qingmo.mcwebui.state.WebStateSubscription::close);
            subscriptions.clear();
            queuedMessages.clear();
            bridge.resetSession();
            if (browser != null) {
                // Clear the previous page's globals for both trusted reloads and untrusted
                // navigations. A subsequent trusted load receives a fresh bootstrap object.
                browser.executeJavaScript("delete window.__MCWEBUI_BRIDGE__; delete window.__MCWEBUI_BRIDGE_DELIVER__;", browser.getURL(), 0);
            }
        }

        private synchronized void send(String encodedMessage) {
            if (closed) return;
            if (!bootstrapInstalled || browser == null) { queuedMessages.addLast(encodedMessage); return; }
            deliverNow(encodedMessage);
        }

        private void deliverNow(String encodedMessage) {
            String script = "window.__MCWEBUI_BRIDGE_DELIVER__&&window.__MCWEBUI_BRIDGE_DELIVER__(" + quote(encodedMessage) + ");";
            browser.executeJavaScript(script, browser.getURL(), 0);
        }

        private String bootstrapScript() {
            return "(() => {const listeners=new Set();let closed=false;const deliver=(m)=>{if(closed)return;try{const v=typeof m==='string'?JSON.parse(m):m;listeners.forEach((l)=>{try{l(v)}catch(_){}})}catch(_){} };const call=(m)=>new Promise((resolve,reject)=>{if(closed){reject(new Error('Bridge closed'));return}try{window.cefQuery({request:JSON.stringify(m),onSuccess:(raw)=>{try{const v=JSON.parse(raw);deliver(v);resolve(v)}catch(e){reject(e)}},onFailure:(code,msg)=>reject(Object.assign(new Error(msg||'Bridge query failed'),{code}))})}catch(e){reject(e)}});window.__MCWEBUI_BRIDGE_DELIVER__=deliver;window.__MCWEBUI_BRIDGE__={connect:()=>call({version:1,type:'handshake'}),send:(m)=>call(m).then(()=>undefined),subscribe:(l)=>{listeners.add(l);return()=>listeners.delete(l)},close:()=>{closed=true;listeners.clear();delete window.__MCWEBUI_BRIDGE__;delete window.__MCWEBUI_BRIDGE_DELIVER__}};window.dispatchEvent(new Event('__MCWEBUI_BRIDGE_READY__'))})()";
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            subscriptions.values().forEach(dev.qingmo.mcwebui.state.WebStateSubscription::close);
            subscriptions.clear();
            eventSubscription.close();
            queuedMessages.clear();
            if (browser != null) browser.executeJavaScript("delete window.__MCWEBUI_BRIDGE__; delete window.__MCWEBUI_BRIDGE_DELIVER__;", browser.getURL(), 0);
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
}
