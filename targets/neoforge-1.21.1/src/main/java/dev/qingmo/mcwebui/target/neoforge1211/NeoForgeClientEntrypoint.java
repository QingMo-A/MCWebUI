package dev.qingmo.mcwebui.target.neoforge1211;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/** Client-only NeoForge wiring for the F8 demo key and mcui scheme registration. */
public final class NeoForgeClientEntrypoint {
    private static final Minecraft MINECRAFT = Minecraft.getInstance();
    private static final BridgeDispatcherHolder BRIDGE = new BridgeDispatcherHolder();
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final KeyMapping OPEN_DEMO = new KeyMapping("Open MCWebUI Runtime Demo",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.misc");
    private static volatile boolean backendReady;

    private NeoForgeClientEntrypoint() { }

    public static void init(IEventBus modEventBus) {
        LOGGER.info("MCWebUI browser backend selection={}", System.getProperty("mcwebui.browserBackend", "mcef"));
        modEventBus.addListener(NeoForgeClientEntrypoint::registerKeys);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEntrypoint::tick);
        if (directCefSelected()) {
            // Direct CEF is intentionally independent from MCEF initialization.  The
            // explicit opt-in owns its native runtime and must still make F8 available
            // when MCEF is absent or failed.
            backendReady = true;
            return;
        }
        NeoForgeMcefBootstrap.init();
    }

    static void markBackendReady(boolean ready) { backendReady = ready; }

    private static void registerKeys(RegisterKeyMappingsEvent event) { event.register(OPEN_DEMO); }

    private static void tick(ClientTickEvent.Post event) {
        if (!backendReady || !OPEN_DEMO.consumeClick()) return;
        if (!(MINECRAFT.screen instanceof NeoForgeMinecraftScreen)) {
            MINECRAFT.setScreen(new NeoForgeMinecraftScreen(BRIDGE.dispatcher, BRIDGE.demo));
        }
    }

    static boolean directCefSelected() {
        return directCefSelected(System.getProperty("mcwebui.browserBackend", "mcef"));
    }

    static boolean directCefSelected(String selection) {
        return NeoForgeBackendSelection.directCefSelected(selection);
    }

    private static final class BridgeDispatcherHolder {
        final dev.qingmo.mcwebui.bridge.BridgeDispatcher dispatcher = new dev.qingmo.mcwebui.bridge.BridgeDispatcher();
        final NeoForgeDemoBridge demo = new NeoForgeDemoBridge();
        BridgeDispatcherHolder() { demo.register(dispatcher); }
    }
}
