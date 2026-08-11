package dev.qingmo.mcwebui.backend;

import dev.qingmo.mcwebui.bridge.WebBridge;
import dev.qingmo.mcwebui.runtime.WebViewConfig;

/** Port implemented by a target-specific browser backend (MCEF/JCEF in a loader module). */
public interface BrowserBackend extends AutoCloseable {
    /** Create a browser surface bound to the view bridge. The bridge is the only host capability port. */
    BrowserSurface createSurface(WebViewConfig config, WebBridge bridge);

    /** Backends may support a standalone surface, but transport-backed surfaces should require a bridge. */
    default BrowserSurface createSurface(WebViewConfig config) {
        return createSurface(config, null);
    }
    @Override
    default void close() { }
}
