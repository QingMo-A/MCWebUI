package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.backend.BrowserSurface;
import dev.qingmo.mcwebui.bridge.BridgeDispatcher;
import dev.qingmo.mcwebui.input.WebKeyEvent;
import dev.qingmo.mcwebui.input.WebMouseEvent;
import dev.qingmo.mcwebui.input.WebScrollEvent;
import dev.qingmo.mcwebui.input.WebTextInputEvent;
import dev.qingmo.mcwebui.runtime.DefaultWebRuntime;
import dev.qingmo.mcwebui.runtime.WebRuntime;
import dev.qingmo.mcwebui.runtime.WebView;
import dev.qingmo.mcwebui.runtime.WebViewConfig;
import dev.qingmo.mcwebui.security.WebOrigin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

/** Real NeoForge Screen boundary: Minecraft APIs stay in this target module. */
public final class NeoForgeMinecraftScreen extends Screen {
    private final WebRuntime runtime;
    private final NeoForgeMcefBackend backend;
    private final BridgeDispatcher dispatcher;
    private final NeoForgeDemoBridge demo;
    private WebView view;
    private BrowserSurface surface;

    public NeoForgeMinecraftScreen(BridgeDispatcher dispatcher, NeoForgeDemoBridge demo) {
        super(Component.literal("MCWebUI Runtime Demo"));
        this.dispatcher = java.util.Objects.requireNonNull(dispatcher, "dispatcher");
        this.demo = java.util.Objects.requireNonNull(demo, "demo");
        this.runtime = new DefaultWebRuntime();
        this.backend = new NeoForgeMcefBackend();
    }

    @Override
    protected void init() {
        view = runtime.createView(new WebViewConfig(WebOrigin.mcui("playground"), "/index.html", Math.max(1, width), Math.max(1, height)));
        view.initialize();
        surface = backend.createSurface(view.config(), frame -> { /* MCEF uploads its texture in onPaint */ });
        view.setVisible(true);
        surface.resize(Math.max(1, (int) (width * minecraft.getWindow().getGuiScale())),
                Math.max(1, (int) (height * minecraft.getWindow().getGuiScale())));
        surface.load("mcui://playground/index.html");
        demo.publishCounter(view.bridge());
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        if (view != null && surface != null) {
            int scaleWidth = Math.max(1, (int) (width * minecraft.getWindow().getGuiScale()));
            int scaleHeight = Math.max(1, (int) (height * minecraft.getWindow().getGuiScale()));
            view.resize(scaleWidth, scaleHeight);
            surface.resize(scaleWidth, scaleHeight);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (surface == null || surface.textureId() < 0) return;
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, surface.textureId());
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(0, height, 0).setUv(0, 1);
        buffer.addVertex(width, height, 0).setUv(1, 1);
        buffer.addVertex(width, 0, 0).setUv(1, 0);
        buffer.addVertex(0, 0, 0).setUv(0, 0);
        BufferUploader.drawWithShader(buffer.build());
        RenderSystem.enableDepthTest();
    }

    @Override public boolean mouseClicked(double x, double y, int button) { input(new WebMouseEvent(WebMouseEvent.Type.DOWN, scaledX(x), scaledY(y), button)); return true; }
    @Override public boolean mouseReleased(double x, double y, int button) { input(new WebMouseEvent(WebMouseEvent.Type.UP, scaledX(x), scaledY(y), button)); return true; }
    @Override public void mouseMoved(double x, double y) { input(new WebMouseEvent(WebMouseEvent.Type.MOVE, scaledX(x), scaledY(y), -1)); }
    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) { input(new WebScrollEvent(scaledX(x), scaledY(y), scrollX, scrollY)); return true; }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) { input(new WebKeyEvent(WebKeyEvent.Type.DOWN, keyCode, modifiers)); return true; }
    @Override public boolean keyReleased(int keyCode, int scanCode, int modifiers) { input(new WebKeyEvent(WebKeyEvent.Type.UP, keyCode, modifiers)); return true; }
    @Override public boolean charTyped(char codePoint, int modifiers) { input(new WebTextInputEvent(String.valueOf(codePoint), false, true)); return true; }

    private void input(dev.qingmo.mcwebui.input.WebInputEvent event) { if (surface != null) surface.input(event); }
    private double scaledX(double value) { return value * minecraft.getWindow().getGuiScale(); }
    private double scaledY(double value) { return value * minecraft.getWindow().getGuiScale(); }

    @Override
    public void onClose() {
        if (surface != null) surface.close();
        if (view != null) view.close();
        runtime.close();
        super.onClose();
    }
}
