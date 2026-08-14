package dev.qingmo.mcwebui.target.neoforge1211;

/** NeoForge render-thread contract for the opt-in direct CEF GPU surface. */
interface DirectCefRenderableSurface extends NeoForgeRenderableSurface {
    void pumpBridge();
    boolean bridgeReady();
    void setVisible(boolean visible);
    void refreshGlContext();
    String runtimeDiagnosticsJson();
}
