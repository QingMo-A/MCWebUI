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

        private ClientConfig() {
            ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
            followGuiSize = builder
                    .comment("Resize the browser viewport whenever Minecraft's GUI size changes.",
                            "Disable to keep the viewport from reallocating during window or GUI-scale resizing.")
                    .translation("mcwebui.config.followGuiSize")
                    .define("followGuiSize", true);
            spec = builder.build();
        }

        boolean followGuiSize() { return followGuiSize.get(); }
    }
}
