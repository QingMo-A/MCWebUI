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
import org.cef.CefApp;
import org.cef.callback.CefSchemeHandlerFactory;
import org.cef.handler.CefAppHandlerAdapter;
import org.lwjgl.glfw.GLFW;

/** Client-only NeoForge wiring for the F8 demo key and mcui scheme registration. */
public final class NeoForgeClientEntrypoint {
    private static final Minecraft MINECRAFT = Minecraft.getInstance();
    private static final BridgeDispatcherHolder BRIDGE = new BridgeDispatcherHolder();
    private static final CefSchemeHandlerFactory MCUI_FACTORY = (browser, frame, schemeName, request) -> {
        String requestUrl = request.getURL();
        return new McuiSchemeHandler(requestUrl);
    };
    public static final KeyMapping OPEN_DEMO = new KeyMapping("Open MCWebUI Runtime Demo",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.misc");
    private static volatile boolean mcefReady;

    private NeoForgeClientEntrypoint() { }

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(NeoForgeClientEntrypoint::registerKeys);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEntrypoint::tick);
        installCustomSchemeRegistration();
        MCEF.scheduleForInit(success -> {
            mcefReady = success;
            if (success) {
                NeoForgeMcefBackend.installRuntimeHooks();
                boolean registered = MCEF.getApp().getHandle().registerSchemeHandlerFactory("mcui", "", MCUI_FACTORY);
                MCEF.getLogger().info("MCWebUI mcui scheme factory registered={} cefState={}", registered, CefApp.getState());
                if (!registered) MCEF.getLogger().error("MCWebUI mcui scheme factory registration failed; bundled views cannot load");
            }
        });
    }

    /** Register mcui during CEF's OnRegisterCustomSchemes callback, before MCEF starts the browser process. */
    private static void installCustomSchemeRegistration() {
        try {
            // Keep the launch switches MCEF's CefUtil passes to CefApp.getInstance(...). Replacing
            // the default app handler with a null-args adapter would otherwise drop them from CEF.
            CefApp.addAppHandler(new CefAppHandlerAdapter(new String[]{
                    "--autoplay-policy=no-user-gesture-required",
                    "--disable-web-security",
                    "--enable-widevine-cdm"
            }) {
                @Override public void onRegisterCustomSchemes(org.cef.callback.CefSchemeRegistrar registrar) {
                    // Standard + secure gives each qualified mcui host a real origin. Keep it
                    // non-local (file-style local schemes have stricter path-scoped CORS rules)
                    // and display-isolated so ordinary web pages cannot embed packaged UI.
                    boolean added = registrar.addCustomScheme("mcui", true, false, true, true, true, false, true);
                    if (!added) MCEF.getLogger().error("MCWebUI mcui custom scheme registration failed");
                }
            });
        } catch (IllegalStateException ex) {
            MCEF.getLogger().warn("MCWebUI could not install mcui custom-scheme app handler", ex);
        }
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
