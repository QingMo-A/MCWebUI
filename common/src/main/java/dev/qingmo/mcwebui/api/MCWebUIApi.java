package dev.qingmo.mcwebui.api;

/**
 * Entry point for the loader-independent MCWebUI Developer Preview API.
 *
 * <p>This API is intentionally marked as a preview.  It is source-compatible
 * only within the current preview line and may change before a stable 1.0
 * contract is published.</p>
 */
public final class MCWebUIApi {
    /** Current Developer Preview API version. */
    public static final int API_VERSION = 1;

    /** Human-readable lifecycle marker for diagnostics and handoff tooling. */
    public static final String RELEASE_CHANNEL = "DEVELOPER_PREVIEW";

    private MCWebUIApi() {
    }
}
