package sample.mcwebui.consumer;

import com.mojang.blaze3d.platform.InputConstants;
import dev.qingmo.mcwebui.api.WebAppDefinition;
import dev.qingmo.mcwebui.api.neoforge.MCWebUIClient;
import dev.qingmo.mcwebui.resource.ClasspathWebResourceProvider;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

import java.util.Map;

/** Small external-mod proof: public MCWebUI API only, with no implementation imports. */
@Mod(value = ConsumerSampleMod.MOD_ID, dist = Dist.CLIENT)
public final class ConsumerSampleMod {
    public static final String MOD_ID = "mcwebui_consumer_sample";
    private static final KeyMapping OPEN_SAMPLE = new KeyMapping(
            "Open MCWebUI consumer sample",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F9,
            "key.categories.mcwebui_consumer_sample");

    public ConsumerSampleMod(IEventBus modEventBus) {
        modEventBus.addListener(this::registerKeys);
        NeoForge.EVENT_BUS.addListener(this::clientTick);

        MCWebUIClient.register(WebAppDefinition.builder("sample:control-panel")
                .resources(new ClasspathWebResourceProvider(
                        ConsumerSampleMod.class.getClassLoader(), "web"))
                .entry("index.html")
                .bridge(dispatcher -> dispatcher.register("sample.echo", request -> Map.of(
                        "message", "echo",
                        "payload", request.payload())))
                .onBridgeCreated(bridge -> bridge.publishState("sample.ready", Map.of(
                        "status", "ready")))
                .build());
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SAMPLE);
    }

    private void clientTick(ClientTickEvent.Post event) {
        if (OPEN_SAMPLE.consumeClick()) {
            MCWebUIClient.open("sample:control-panel");
        }
    }
}
