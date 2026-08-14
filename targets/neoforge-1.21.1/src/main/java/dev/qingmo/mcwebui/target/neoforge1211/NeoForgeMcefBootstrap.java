package dev.qingmo.mcwebui.target.neoforge1211;

import com.cinemamod.mcef.MCEF;
import org.cef.CefApp;
import org.cef.callback.CefSchemeHandlerFactory;
import org.cef.handler.CefAppHandlerAdapter;

/** MCEF-only bootstrap kept out of the direct-Cef class-loading path. */
final class NeoForgeMcefBootstrap {
    private static final CefSchemeHandlerFactory MCUI_FACTORY = (browser, frame, schemeName, request) ->
            new McuiSchemeHandler(request.getURL());

    private NeoForgeMcefBootstrap() { }

    static void init() {
        installCustomSchemeRegistration();
        MCEF.scheduleForInit(success -> {
            NeoForgeClientEntrypoint.markBackendReady(success);
            if (success) {
                NeoForgeMcefBackend.installRuntimeHooks();
                boolean registered = MCEF.getApp().getHandle().registerSchemeHandlerFactory("mcui", "", MCUI_FACTORY);
                MCEF.getLogger().info("MCWebUI mcui scheme factory registered={} cefState={}", registered, CefApp.getState());
                if (!registered) MCEF.getLogger().error("MCWebUI mcui scheme factory registration failed; bundled views cannot load");
            }
        });
    }

    private static void installCustomSchemeRegistration() {
        try {
            CefApp.addAppHandler(new CefAppHandlerAdapter(new String[]{
                    // The showcase intentionally starts media without a gesture. This is
                    // independent from origin/CORS enforcement and can be removed by an
                    // embedding application that does not need autoplay.
                    "--autoplay-policy=no-user-gesture-required"
            }) {
                @Override public void onRegisterCustomSchemes(org.cef.callback.CefSchemeRegistrar registrar) {
                    boolean added = registrar.addCustomScheme("mcui", true, false, true, true, true, false, true);
                    if (!added) MCEF.getLogger().error("MCWebUI mcui custom scheme registration failed");
                }
            });
        } catch (IllegalStateException ex) {
            MCEF.getLogger().warn("MCWebUI could not install mcui custom-scheme app handler", ex);
        }
    }
}
