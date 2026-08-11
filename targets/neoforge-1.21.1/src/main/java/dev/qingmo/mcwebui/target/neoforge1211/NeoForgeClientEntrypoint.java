package dev.qingmo.mcwebui.target.neoforge1211;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.listeners.MCEFInitListener;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Client-only NeoForge wiring for the F8 demo key and mcui scheme registration. */
public final class NeoForgeClientEntrypoint {
    private static final Minecraft MINECRAFT = Minecraft.getInstance();
    private static final BridgeDispatcherHolder BRIDGE = new BridgeDispatcherHolder();
    public static final KeyMapping OPEN_DEMO = new KeyMapping("Open MCWebUI Runtime Demo",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.misc");
    private static volatile boolean mcefReady;

    private NeoForgeClientEntrypoint() { }

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(NeoForgeClientEntrypoint::registerKeys);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEntrypoint::tick);
        MCEF.scheduleForInit(success -> {
            mcefReady = success;
            if (success) {
                NeoForgeMcefBackend.installRuntimeHooks();
                MCEF.getApp().getHandle().registerSchemeHandlerFactory("mcui", "", (browser, frame, url, request) -> new McuiSchemeHandler(url));
            }
        });
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) { event.register(OPEN_DEMO); }

    private static void tick(ClientTickEvent.Post event) {
        if (!mcefReady || !OPEN_DEMO.consumeClick()) return;
        if (!(MINECRAFT.screen instanceof NeoForgeMinecraftScreen)) {
            MINECRAFT.setScreen(new NeoForgeMinecraftScreen(BRIDGE.dispatcher, BRIDGE.demo));
        }
    }

    private static final class BridgeDispatcherHolder {
        final dev.qingmo.mcwebui.bridge.BridgeDispatcher dispatcher = new dev.qingmo.mcwebui.bridge.BridgeDispatcher();
        final NeoForgeDemoBridge demo = new NeoForgeDemoBridge();
        BridgeDispatcherHolder() { demo.register(dispatcher); }
    }
}
