package dev.qingmo.mcwebui.target.neoforge1211;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(NeoForgeMod.MOD_ID)
public final class NeoForgeMod {
    public static final String MOD_ID = "mcwebui";

    public NeoForgeMod(IEventBus modEventBus, ModContainer modContainer) {
        if (FMLEnvironment.dist.isClient()) {
            NeoForgeClientEntrypoint.init(modEventBus);
        }
    }
}
