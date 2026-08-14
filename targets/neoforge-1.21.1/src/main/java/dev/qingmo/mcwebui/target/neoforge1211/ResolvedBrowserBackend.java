package dev.qingmo.mcwebui.target.neoforge1211;

enum ResolvedBrowserBackend {
    MCEF("mcef"),
    DIRECT_CEF("direct-cef"),
    NONE("none");

    private final String externalName;
    ResolvedBrowserBackend(String externalName) { this.externalName = externalName; }
    String externalName() { return externalName; }
}
