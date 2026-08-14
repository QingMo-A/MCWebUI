package dev.qingmo.mcwebui.api.neoforge;

import dev.qingmo.mcwebui.api.MCWebUIApi;
import dev.qingmo.mcwebui.api.WebAppDefinition;
import dev.qingmo.mcwebui.api.WebAppId;
import dev.qingmo.mcwebui.api.WebAppRegistry;
import dev.qingmo.mcwebui.target.neoforge1211.NeoForgeClientEntrypoint;
import net.minecraft.client.gui.screens.Screen;

import java.util.Objects;

/**
 * NeoForge 1.21.1 Developer Preview entry point for registered WebScreens.
 * Consumer mods do not need to reference MCWebUI's MCEF, CEF, JNI or renderer internals.
 */
public final class NeoForgeWebScreens {
    public static final int API_VERSION = MCWebUIApi.API_VERSION;

    private NeoForgeWebScreens() { }

    public static WebAppDefinition register(WebAppDefinition definition) {
        return WebAppRegistry.process().register(Objects.requireNonNull(definition, "definition"));
    }

    public static Screen createScreen(WebAppId id) {
        return NeoForgeClientEntrypoint.createRegisteredWebScreen(Objects.requireNonNull(id, "id"));
    }

    public static Screen createScreen(String id) {
        return createScreen(WebAppId.parse(id));
    }

    public static void open(WebAppId id) {
        NeoForgeClientEntrypoint.openRegisteredWebApp(Objects.requireNonNull(id, "id"));
    }

    public static void open(String id) {
        open(WebAppId.parse(id));
    }
}
