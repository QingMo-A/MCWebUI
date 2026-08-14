package dev.qingmo.mcwebui.target.neoforge1211;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.ModConfigSpec;

@Mod(NeoForgeMod.MOD_ID)
public final class NeoForgeMod {
    public static final String MOD_ID = "mcwebui";
    static final ClientConfig CLIENT_CONFIG = new ClientConfig();

    public NeoForgeMod(IEventBus modEventBus, ModContainer modContainer) {
        if (FMLEnvironment.dist.isClient()) {
            modContainer.registerConfig(ModConfig.Type.CLIENT, CLIENT_CONFIG.spec);
            NeoForgeClientEntrypoint.init(modEventBus);
        }
    }

    /** Persistent client-only display preferences used by the active browser Screen. */
    static final class ClientConfig {
        private final ModConfigSpec spec;
        private final ModConfigSpec.BooleanValue followGuiSize;
        private final ModConfigSpec.EnumValue<BrowserBackendPreference> browserBackend;

        private ClientConfig() {
            ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
            followGuiSize = builder
                    .comment("Use Minecraft's logical GUI size for the browser viewport.",
                            "Disable to preserve GUI-scale-1 CSS density with framebuffer-equivalent pixels.")
                    .translation("mcwebui.config.followGuiSize")
                    .define("followGuiSize", true);
            browserBackend = builder
                    .comment("Browser backend used by MCWebUI.",
                            "MCEF preserves the historical default; AUTO prefers installed MCEF then Direct CEF.",
                            "The -Dmcwebui.browserBackend JVM property overrides this value for developer runs.")
                    .translation("mcwebui.config.browserBackend")
                    .defineEnum("browserBackend", BrowserBackendPreference.MCEF);
            spec = builder.build();
        }

        boolean followGuiSize() { return followGuiSize.get(); }
        BrowserBackendPreference browserBackend() { return browserBackend.get(); }
    }
}
