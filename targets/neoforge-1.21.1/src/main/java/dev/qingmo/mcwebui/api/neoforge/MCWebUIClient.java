package dev.qingmo.mcwebui.api.neoforge;

import dev.qingmo.mcwebui.api.MCWebUIApi;
import dev.qingmo.mcwebui.api.WebAppDefinition;
import dev.qingmo.mcwebui.api.WebAppId;
import net.minecraft.client.gui.screens.Screen;

/** Concise public facade for the NeoForge Developer Preview WebScreen API. */
public final class MCWebUIClient {
    public static final int API_VERSION = MCWebUIApi.API_VERSION;

    private MCWebUIClient() { }

    public static WebAppDefinition register(WebAppDefinition definition) {
        return NeoForgeWebScreens.register(definition);
    }

    public static Screen createScreen(WebAppId id) { return NeoForgeWebScreens.createScreen(id); }
    public static Screen createScreen(String id) { return NeoForgeWebScreens.createScreen(id); }
    public static void open(WebAppId id) { NeoForgeWebScreens.open(id); }
    public static void open(String id) { NeoForgeWebScreens.open(id); }

    public static MCWebUIBackendStatus backendStatus() {
        return dev.qingmo.mcwebui.target.neoforge1211.NeoForgeClientEntrypoint.backendStatus();
    }

    public static MCWebUIEnvironment environment() {
        return dev.qingmo.mcwebui.target.neoforge1211.NeoForgeClientEntrypoint.environment();
    }
}
