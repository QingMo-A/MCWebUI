package dev.qingmo.mcwebui.runtime;

import dev.qingmo.mcwebui.bridge.BridgeDispatcher;
import dev.qingmo.mcwebui.bridge.WebBridge;
import dev.qingmo.mcwebui.input.WebInputEvent;
import dev.qingmo.mcwebui.security.WebPermissionPolicy;
import dev.qingmo.mcwebui.state.WebStateStore;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Backend-neutral lifecycle implementation used by target adapters. */
public final class DefaultWebRuntime implements WebRuntime {
    private final Map<String, DefaultWebView> views = new ConcurrentHashMap<>();
    private final WebPermissionPolicy permissions;
    private final BridgeDispatcher dispatcher;
    private volatile boolean closed;

    public DefaultWebRuntime(WebPermissionPolicy permissions, BridgeDispatcher dispatcher) {
        this.permissions = permissions;
        this.dispatcher = dispatcher;
    }

    public DefaultWebRuntime() {
        this(new WebPermissionPolicy(), new BridgeDispatcher());
    }

    @Override
    public WebView createView(WebViewConfig config) {
        if (closed) throw new IllegalStateException("Runtime is closed");
        DefaultWebView view = new DefaultWebView(UUID.randomUUID().toString(), config,
                new WebBridge(config.origin(), permissions, dispatcher, new WebStateStore()), this);
        views.put(view.id(), view);
        return view;
    }

    void remove(DefaultWebView view) { views.remove(view.id(), view); }
    public int liveViewCount() { return views.size(); }

    @Override
    public void close() {
        closed = true;
        views.values().forEach(DefaultWebView::close);
        views.clear();
    }

    private static final class DefaultWebView implements WebView {
        private final String id;
        private final WebViewConfig config;
        private final WebBridge bridge;
        private final DefaultWebRuntime runtime;
        private volatile WebViewState state;

        private DefaultWebView(String id, WebViewConfig config, WebBridge bridge, DefaultWebRuntime runtime) {
            this.id = id;
            this.config = config;
            this.bridge = bridge;
            this.runtime = runtime;
            this.state = new WebViewState(WebViewLifecycle.CREATED, config.width(), config.height(), false);
        }

        @Override public String id() { return id; }
        @Override public WebViewConfig config() { return config; }
        @Override public WebViewState state() { return state; }
        @Override public WebBridge bridge() { return bridge; }
        @Override public WebStateStore stateStore() { return bridge.stateStore(); }

        @Override
        public synchronized void initialize() {
            if (state.lifecycle() == WebViewLifecycle.CLOSED || state.lifecycle() == WebViewLifecycle.DISPOSED) return;
            if (state.lifecycle() != WebViewLifecycle.CREATED) return;
            state = new WebViewState(WebViewLifecycle.INITIALIZING, state.width(), state.height(), state.focused());
            try {
                bridge.handshake();
                state = new WebViewState(WebViewLifecycle.READY, state.width(), state.height(), state.focused());
            } catch (RuntimeException ex) {
                state = new WebViewState(WebViewLifecycle.FAILED, state.width(), state.height(), state.focused());
                throw ex;
            }
        }

        @Override
        public synchronized void setVisible(boolean visible) {
            if (state.lifecycle() == WebViewLifecycle.CREATED) initialize();
            if (state.lifecycle() == WebViewLifecycle.READY || state.lifecycle() == WebViewLifecycle.HIDDEN
                    || state.lifecycle() == WebViewLifecycle.VISIBLE) {
                state = new WebViewState(visible ? WebViewLifecycle.VISIBLE : WebViewLifecycle.HIDDEN,
                        state.width(), state.height(), state.focused());
            }
        }

        @Override
        public synchronized void resize(int width, int height) {
            if (width < 1 || height < 1) throw new IllegalArgumentException("view dimensions must be positive");
            if (state.lifecycle() == WebViewLifecycle.CLOSED || state.lifecycle() == WebViewLifecycle.DISPOSED) return;
            state = new WebViewState(state.lifecycle(), width, height, state.focused());
        }

        @Override
        public synchronized void focus(boolean focused) {
            if (state.lifecycle() == WebViewLifecycle.CLOSED || state.lifecycle() == WebViewLifecycle.DISPOSED) return;
            state = new WebViewState(state.lifecycle(), state.width(), state.height(), focused);
        }

        @Override
        public void dispatchInput(WebInputEvent event) {
            if (state.lifecycle() != WebViewLifecycle.VISIBLE) return;
            if (event == null) throw new NullPointerException("event");
        }

        @Override
        public synchronized void close() {
            if (state.lifecycle() == WebViewLifecycle.DISPOSED) return;
            state = new WebViewState(WebViewLifecycle.CLOSING, state.width(), state.height(), false);
            bridge.close();
            state = new WebViewState(WebViewLifecycle.CLOSED, state.width(), state.height(), false);
            state = new WebViewState(WebViewLifecycle.DISPOSED, state.width(), state.height(), false);
            runtime.remove(this);
        }
    }
}
