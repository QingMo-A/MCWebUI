package dev.qingmo.mcwebui.target.neoforge1211;

/** Pure property selection helpers kept free of Minecraft static initialization for tests. */
final class NeoForgeBackendSelection {
    private NeoForgeBackendSelection() { }
    static boolean directCefSelected(String selection) {
        return selection != null && "direct-cef".equalsIgnoreCase(selection.trim());
    }

    static boolean shouldOpenWarmSession(boolean requested, boolean sessionPresent,
                                         boolean renderableFrame, boolean alreadyOpen) {
        return requested && sessionPresent && renderableFrame && !alreadyOpen;
    }
}
