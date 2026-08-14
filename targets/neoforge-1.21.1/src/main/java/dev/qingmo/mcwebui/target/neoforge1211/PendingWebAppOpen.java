package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.api.WebAppId;

import java.util.Objects;

/** Single-slot continuation used while the Direct CEF setup screen owns the UI. */
final class PendingWebAppOpen {
    private WebAppId requested;

    synchronized void save(WebAppId id) { requested = Objects.requireNonNull(id, "id"); }
    synchronized WebAppId take() {
        WebAppId result = requested;
        requested = null;
        return result;
    }
    synchronized void clear() { requested = null; }
    synchronized WebAppId peek() { return requested; }
}
