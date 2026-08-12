package dev.qingmo.mcwebui.backend;

/** Optional backend-neutral frame pacing capability. Unsupported surfaces remain callback-driven. */
public interface BrowserFramePacing {
    default boolean supportsExternalFrames() { return false; }
    default boolean requestExternalFrame() { return false; }
}
