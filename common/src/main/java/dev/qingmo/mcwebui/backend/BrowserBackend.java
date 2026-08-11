package dev.qingmo.mcwebui.backend;

import dev.qingmo.mcwebui.runtime.WebViewConfig;

/** Port implemented by a target-specific browser backend (MCEF/JCEF in a loader module). */
public interface BrowserBackend extends AutoCloseable {
    BrowserSurface createSurface(WebViewConfig config, BrowserSurfaceListener listener);
    @Override
    default void close() { }
}
