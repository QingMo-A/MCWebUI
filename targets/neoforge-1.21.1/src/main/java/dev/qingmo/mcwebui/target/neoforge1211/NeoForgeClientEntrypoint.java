package dev.qingmo.mcwebui.target.neoforge1211;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.qingmo.mcwebui.nativecef.DirectCefRuntimeDiscovery;
import dev.qingmo.mcwebui.nativecef.DirectCefRuntimeException;
import dev.qingmo.mcwebui.api.WebAppDefinition;
import dev.qingmo.mcwebui.api.WebAppId;
import dev.qingmo.mcwebui.api.WebAppRegistry;
import dev.qingmo.mcwebui.api.neoforge.MCWebUIBackendStatus;
import dev.qingmo.mcwebui.api.neoforge.MCWebUIEnvironment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
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
    private static volatile NeoForgeBackendSelection.Resolution backendResolution =
            new NeoForgeBackendSelection.Resolution(BrowserBackendPreference.MCEF,
                    ResolvedBrowserBackend.NONE, BackendAvailability.INITIALIZATION_FAILED,
                    "Backend selection has not been initialized.");
    private static volatile String backendFailure = "";
    private static NeoForgeWebSession warmSession;
    private static NeoForgeWebSession directSession;
    private static boolean warmFrameLogged;
    private static boolean openWhenWarm;
    private static boolean directWarmFrameLogged;
    private static boolean directWarmAttempted;
    private static NeoForgeWebSession publicSession;
    private static final PendingWebAppOpen PENDING_PUBLIC_APP = new PendingWebAppOpen();

    private NeoForgeClientEntrypoint() { }

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(NeoForgeClientEntrypoint::registerKeys);
        modEventBus.addListener(NeoForgeClientEntrypoint::clientSetup);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEntrypoint::tick);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEntrypoint::shutdown);
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        // Client config values are loaded by this lifecycle point. Resolving in the
        // mod constructor would silently use defaults and could bootstrap the wrong CEF.
        event.enqueueWork(NeoForgeClientEntrypoint::initializeSelectedBackend);
    }

    private static void initializeSelectedBackend() {
        resolveBackendSelection();
        LOGGER.info("MCWebUI browser preference={} resolved={} availability={}",
                backendResolution.preference().externalName(), backendResolution.backend().externalName(),
                backendResolution.availability());
        if (backendResolution.availability() != BackendAvailability.AVAILABLE) return;
        if (directCefSelected()) {
            // Direct CEF is intentionally independent from MCEF initialization.  The
            // explicit opt-in owns its native runtime and must still make F8 available
            // when MCEF is absent or failed.
            backendReady = true;
            return;
        }
        NeoForgeMcefBootstrap.init();
    }

    static void markBackendReady(boolean ready) {
        backendReady = ready;
        if (ready) {
            backendFailure = "";
            backendResolution = new NeoForgeBackendSelection.Resolution(backendResolution.preference(),
                    ResolvedBrowserBackend.MCEF, BackendAvailability.AVAILABLE, "");
        } else {
            backendFailure = "CinemaMod MCEF initialization failed. Check the client log and retry.";
            backendResolution = new NeoForgeBackendSelection.Resolution(backendResolution.preference(),
                    ResolvedBrowserBackend.MCEF, BackendAvailability.INITIALIZATION_FAILED, backendFailure);
        }
    }

    private static void resolveBackendSelection() {
        backendReady = false;
        String property = System.getProperty("mcwebui.browserBackend");
        try {
            BrowserBackendPreference preference = NeoForgeBackendSelection.preference(property,
                    NeoForgeMod.CLIENT_CONFIG.browserBackend());
            backendResolution = NeoForgeBackendSelection.resolve(preference,
                    ModList.get().isLoaded("mcef"), isWindows());
            backendFailure = backendResolution.reason();
        } catch (RuntimeException invalid) {
            String value = property == null ? String.valueOf(NeoForgeMod.CLIENT_CONFIG.browserBackend()) : property;
            backendFailure = "Invalid browser backend preference: " + value;
            backendResolution = new NeoForgeBackendSelection.Resolution(BrowserBackendPreference.MCEF,
                    ResolvedBrowserBackend.NONE, BackendAvailability.INITIALIZATION_FAILED, backendFailure);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) { event.register(OPEN_DEMO); }

    private static void tick(ClientTickEvent.Post event) {
        boolean clicked = OPEN_DEMO.consumeClick();
        if (!backendReady) {
            if (clicked) openUnavailableScreen();
            return;
        }
        boolean direct = directCefSelected();
        if (direct) ensureDirectWarmSession();
        if (directSession != null && !directSession.isClosed()) {
            try {
                directSession.pumpBridge();
                if (directSession.prewarmTick(System.nanoTime()) && !directWarmFrameLogged) {
                    directWarmFrameLogged = true;
                    LOGGER.info("MCWebUI Direct CEF prewarm completed with an accelerated texture and bridge handshake");
                }
            } catch (RuntimeException failure) {
                LOGGER.error("MCWebUI Direct CEF bridge pump failed", failure);
            }
        }
        if (publicSession != null && !publicSession.isClosed()) {
            try {
                publicSession.pumpBridge();
            } catch (RuntimeException failure) {
                LOGGER.error("MCWebUI registered WebApp bridge pump failed for {}",
                        publicSession.app().id(), failure);
            }
        }
        if (!direct) ensureMcefWarmSession();
        if (warmSession != null && !warmFrameLogged && warmSession.hasRenderableFrame()) {
            warmFrameLogged = true;
            LOGGER.info("MCWebUI MCEF warm session produced its first renderable frame");
        }
        if (direct) {
            if (clicked && !(MINECRAFT.screen instanceof NeoForgeMinecraftScreen)
                    && !(MINECRAFT.screen instanceof DirectCefRuntimeSetupScreen)) {
                DirectCefRuntimeDiscovery.Probe probe = probeDirectRuntime();
                if (probe.valid()) {
                    markDirectRuntimeAvailable();
                    openDirectWebScreen();
                } else {
                    // A missing/corrupt runtime must open the recovery screen instead of
                    // silently doing nothing after a stack trace in the log.
                    LOGGER.warn("MCWebUI Direct CEF runtime is unavailable ({}); opening the runtime setup screen",
                            probe.failure().reason());
                    markDirectRuntimeMissing(probe);
                    openDirectSetupScreen(probe);
                }
            }
            return;
        }
        if (clicked && !(MINECRAFT.screen instanceof NeoForgeMinecraftScreen)) {
            openWhenWarm = true;
            if (!warmFrameLogged) LOGGER.info("MCWebUI F8 requested while MCEF warm session is still preparing");
        }
        if (NeoForgeBackendSelection.shouldOpenWarmSession(openWhenWarm, warmSession != null,
                warmSession != null && warmSession.hasRenderableFrame(),
                MINECRAFT.screen instanceof NeoForgeMinecraftScreen)) {
            openWhenWarm = false;
            warmSession.activate();
            MINECRAFT.setScreen(new NeoForgeMinecraftScreen(warmSession));
        }
    }

    static void openDirectWebScreen() {
        try {
            closePublicSession();
            if (directSession == null || directSession.isClosed()) {
                NeoForgeWebSession session = new NeoForgeWebSession(BRIDGE.dispatcher, BRIDGE.demo);
                session.warmUp(MINECRAFT.getWindow().getGuiScaledWidth(),
                        MINECRAFT.getWindow().getGuiScaledHeight(),
                        MINECRAFT.getWindow().getGuiScale());
                directSession = session;
            }
            directSession.activate();
            // Keep the process-global CEF runtime alive across ESC. Repeated
            // CefInitialize/CefShutdown cycles from Screen removal are unsafe and
            // were the source of the libcef fullscreen/ESC crash.
            MINECRAFT.setScreen(new NeoForgeMinecraftScreen(directSession));
        } catch (Throwable failure) {
            // Native proof configuration must never tear down the whole client from a
            // key event. Direct mode still does not fall back to MCEF; it reports the
            // explicit failure and leaves the current Minecraft screen intact.
            LOGGER.error("MCWebUI Direct CEF screen failed to open", failure);
            backendFailure = "Direct CEF initialization failed: " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : " - " + failure.getMessage());
            backendResolution = new NeoForgeBackendSelection.Resolution(backendResolution.preference(),
                    ResolvedBrowserBackend.DIRECT_CEF, BackendAvailability.INITIALIZATION_FAILED, backendFailure);
            if (MINECRAFT.screen instanceof NeoForgeMinecraftScreen) MINECRAFT.setScreen(null);
            if (directSession != null) {
                directSession.close();
                directSession = null;
            }
            if (failure instanceof DirectCefRuntimeException runtimeFailure) {
                // A runtime problem surfaced during open: route the player to setup
                // instead of leaving F8 dead.
                DirectCefRuntimeDiscovery.Probe probe = probeDirectRuntime();
                if (!probe.valid()) {
                    LOGGER.warn("MCWebUI Direct CEF runtime is unavailable ({}); opening the runtime setup screen",
                            probe.failure().reason());
                    openDirectSetupScreen(probe);
                } else {
                    MINECRAFT.setScreen(unavailableScreen(MINECRAFT.screen));
                }
            } else {
                MINECRAFT.setScreen(unavailableScreen(MINECRAFT.screen));
            }
        }
    }

    /** Internal target hook used by the public Developer Preview facade. */
    public static Screen createRegisteredWebScreen(WebAppId id) {
        if (!MINECRAFT.isSameThread()) {
            throw new IllegalStateException("createScreen must be called on the Minecraft client thread; use open instead");
        }
        WebAppDefinition definition = WebAppRegistry.process().require(id);
        if (!backendReady) return unavailableScreen(MINECRAFT.screen);
        if (directCefSelected()) {
            DirectCefRuntimeDiscovery.Probe probe = probeDirectRuntime();
            if (!probe.valid()) {
                markDirectRuntimeMissing(probe);
                PENDING_PUBLIC_APP.save(id);
                Screen previous = MINECRAFT.screen;
                return new DirectCefRuntimeSetupScreen(NeoForgeWebSession.directCefInstanceRoot(),
                        probe, previous, NeoForgeClientEntrypoint::continuePendingPublicApp,
                        PENDING_PUBLIC_APP::clear);
            }
            markDirectRuntimeAvailable();
            // One Direct CEF owner is permitted in the preview. Do not leave the F8
            // playground alive when a consumer app takes ownership of the runtime.
            if (directSession != null) {
                directSession.close();
                directSession = null;
            }
        }
        if (publicSession != null && !publicSession.isClosed()
                && publicSession.app().id().equals(id)) {
            publicSession.activate();
            return new NeoForgeMinecraftScreen(publicSession);
        }
        closePublicSession();
        publicSession = new NeoForgeWebSession(definition);
        return new NeoForgeMinecraftScreen(publicSession);
    }

    /** Schedules a registered WebApp open on Minecraft's client thread. */
    public static void openRegisteredWebApp(WebAppId id) {
        WebAppId requested = java.util.Objects.requireNonNull(id, "id");
        // Fail synchronously with the registry's structured unknown-ID error instead
        // of throwing later from an opaque scheduled client callback.
        WebAppRegistry.process().require(requested);
        MINECRAFT.execute(() -> {
            Screen next = createRegisteredWebScreen(requested);
            MINECRAFT.setScreen(next);
        });
    }

    private static void continuePendingPublicApp() {
        WebAppId requested = PENDING_PUBLIC_APP.take();
        if (requested != null) openRegisteredWebApp(requested);
    }

    private static Screen unavailableScreen(Screen previous) {
        String reason = backendFailure.isBlank() ? backendResolution.reason() : backendFailure;
        return new MCWebUIBackendUnavailableScreen(previous, backendResolution.backend().externalName(),
                reason.isBlank() ? "The selected backend is not ready." : reason, () -> {
            resolveBackendSelection();
            if (backendResolution.availability() == BackendAvailability.AVAILABLE) {
                if (backendResolution.backend() == ResolvedBrowserBackend.DIRECT_CEF) backendReady = true;
                else NeoForgeMcefBootstrap.init();
            }
            if (backendReady) MINECRAFT.setScreen(previous);
            else MINECRAFT.setScreen(unavailableScreen(previous));
        });
    }

    private static void openUnavailableScreen() { MINECRAFT.setScreen(unavailableScreen(MINECRAFT.screen)); }

    public static MCWebUIBackendStatus backendStatus() {
        return new MCWebUIBackendStatus(backendResolution.preference().externalName(),
                backendResolution.backend().externalName(), backendResolution.availability().name(),
                backendFailure.isBlank() ? backendResolution.reason() : backendFailure);
    }

    public static MCWebUIEnvironment environment() {
        String os = System.getProperty("os.name", "unknown");
        return new MCWebUIEnvironment("neoforge-1.21.1", "NeoForge", "1.21.1",
                Runtime.version().feature(), os, isWindows(), ModList.get().isLoaded("mcef"));
    }

    private static void closePublicSession() {
        if (publicSession != null) {
            publicSession.close();
            publicSession = null;
        }
    }

    static DirectCefRuntimeDiscovery.Probe probeDirectRuntime() {
        String runtimeOverride = System.getProperty(DirectCefRuntimeDiscovery.RUNTIME_OVERRIDE_PROPERTY, "").trim();
        return DirectCefRuntimeDiscovery.probe(NeoForgeWebSession.directCefInstanceRoot(),
                runtimeOverride.isEmpty() ? null : java.nio.file.Path.of(runtimeOverride));
    }

    private static void openDirectSetupScreen(DirectCefRuntimeDiscovery.Probe probe) {
        Screen previous = MINECRAFT.screen;
        MINECRAFT.setScreen(new DirectCefRuntimeSetupScreen(NeoForgeWebSession.directCefInstanceRoot(),
                probe, previous, NeoForgeClientEntrypoint::openDirectWebScreen));
    }

    private static void markDirectRuntimeMissing(DirectCefRuntimeDiscovery.Probe probe) {
        backendFailure = probe.failure() == null ? "Direct CEF runtime is missing or invalid."
                : probe.failure().getMessage();
        backendResolution = new NeoForgeBackendSelection.Resolution(backendResolution.preference(),
                ResolvedBrowserBackend.DIRECT_CEF, BackendAvailability.RUNTIME_MISSING, backendFailure);
    }

    private static void markDirectRuntimeAvailable() {
        backendFailure = "";
        backendResolution = new NeoForgeBackendSelection.Resolution(backendResolution.preference(),
                ResolvedBrowserBackend.DIRECT_CEF, BackendAvailability.AVAILABLE, "");
    }

    private static void ensureDirectWarmSession() {
        if (publicSession != null && !publicSession.isClosed()) return;
        if (directSession != null && !directSession.isClosed()) return;
        // Client ticks begin while NeoForge is still bringing up its loading window. Wait
        // until Minecraft owns a real screen so the synchronous CEF/JNI setup cost is paid
        // during normal client loading instead of contending with bootstrap callbacks.
        if (MINECRAFT.screen == null) return;
        if (directWarmAttempted) return;
        int width = MINECRAFT.getWindow().getGuiScaledWidth();
        int height = MINECRAFT.getWindow().getGuiScaledHeight();
        if (width < 1 || height < 1) return;
        directWarmAttempted = true;
        try {
            NeoForgeWebSession session = new NeoForgeWebSession(BRIDGE.dispatcher, BRIDGE.demo);
            session.warmUp(width, height, MINECRAFT.getWindow().getGuiScale());
            directSession = session;
            directWarmFrameLogged = false;
            LOGGER.info("MCWebUI Direct CEF hidden prewarm started at {}x{}", width, height);
        } catch (Throwable ex) {
            // A partly initialized CEF process is not safe to retry every tick. Keep the
            // client alive and let an explicit F8 press make one normal cold-start attempt.
            LOGGER.warn("MCWebUI Direct CEF prewarm could not start; F8 will retry on demand", ex);
        }
    }

    private static void ensureMcefWarmSession() {
        if (warmSession != null && !warmSession.isClosed()) return;
        int width = MINECRAFT.getWindow().getGuiScaledWidth();
        int height = MINECRAFT.getWindow().getGuiScaledHeight();
        if (width < 1 || height < 1) return;
        try {
            NeoForgeWebSession session = new NeoForgeWebSession(BRIDGE.dispatcher, BRIDGE.demo);
            session.warmUp(width, height, MINECRAFT.getWindow().getGuiScale());
            warmSession = session;
            warmFrameLogged = false;
            LOGGER.info("MCWebUI MCEF warm session started at {}x{}", width, height);
        } catch (RuntimeException ex) {
            // Prewarming is an optimization. Keep F8/client startup recoverable and retry on a
            // later client tick instead of turning a cold-start optimization into a crash.
            LOGGER.warn("MCWebUI MCEF warm session could not start; will retry", ex);
        }
    }

    private static void shutdown(GameShuttingDownEvent event) {
        if (warmSession != null) {
            warmSession.close();
            warmSession = null;
        }
        if (directSession != null) {
            directSession.close();
            directSession = null;
        }
        closePublicSession();
        PENDING_PUBLIC_APP.clear();
        warmFrameLogged = false;
        openWhenWarm = false;
        directWarmFrameLogged = false;
        directWarmAttempted = false;
    }

    static boolean directCefSelected() {
        return backendResolution.backend() == ResolvedBrowserBackend.DIRECT_CEF;
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
