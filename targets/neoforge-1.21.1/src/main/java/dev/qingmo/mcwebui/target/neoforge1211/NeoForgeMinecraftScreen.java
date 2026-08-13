package dev.qingmo.mcwebui.target.neoforge1211;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.qingmo.mcwebui.bridge.BridgeDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** The sole NeoForge Screen boundary; all browser/session work lives in NeoForgeWebSession. */
public final class NeoForgeMinecraftScreen extends Screen {
    private final NeoForgeWebSession session;

    public NeoForgeMinecraftScreen(BridgeDispatcher dispatcher, NeoForgeDemoBridge demo) {
        super(Component.literal("MCWebUI Runtime Demo"));
        this.session = new NeoForgeWebSession(dispatcher, demo);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        session.init(width, height, minecraft.getWindow().getGuiScale());
    }

    @Override public void resize(Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        session.resize(width, height, minecraft.getWindow().getGuiScale());
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (session.isDirectBackend()) {
            // Screen.render() invokes renderBackground(), which paints an opaque menu over
            // the world. Direct CEF is a transparent overlay, so render only children here.
            for (var renderable : renderables) renderable.render(graphics, mouseX, mouseY, partialTick);
        } else {
            // Keep stock MCEF's established Screen/background behavior byte-for-byte.
            super.render(graphics, mouseX, mouseY, partialTick);
        }
        NeoForgeRenderableSurface surface = session.surface();
        if (surface == null) return;
        // One active, visible host render gives an opt-in backend at most one
        // non-blocking begin-frame opportunity. Stock MCEF remains callback-driven.
        session.beginFrame(System.nanoTime());
        DirectCefRenderableSurface direct = surface instanceof DirectCefRenderableSurface d ? d : null;
        boolean directLocked = direct == null || direct.beginRenderFrame();
        if (!directLocked) return;
        int textureId = surface.textureId();
        // MCEF exposes texture id 0 until its render-thread initialization has completed;
        // binding it would draw the default texture and make the screen look permanently blank.
        if (textureId <= 0) { if (direct != null) direct.endRenderFrame(); return; }
        int previousTexture = RenderSystem.getShaderTexture(0);
        RenderSystem.disableDepthTest();
        // MCEF's surface is created opaque; leave the same post-draw state as the previous
        // transparent path while avoiding a blend pass for the full-screen browser quad.
        if (direct != null) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        if (direct != null) RenderSystem.blendFuncSeparate(770, 771, 1, 771);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, textureId);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        var pose = graphics.pose().last().pose();
        boolean flip = direct != null && direct.yFlipped();
        buffer.addVertex(pose, 0, 0, 0).setUv(0, flip ? 1 : 0);
        buffer.addVertex(pose, 0, height, 0).setUv(0, flip ? 0 : 1);
        buffer.addVertex(pose, width, height, 0).setUv(1, flip ? 0 : 1);
        buffer.addVertex(pose, width, 0, 0).setUv(1, flip ? 1 : 0);
        try {
            BufferUploader.drawWithShader(buffer.buildOrThrow());
        }
        finally {
            if (direct != null) { direct.endRenderFrame(); RenderSystem.disableBlend(); }
            if (direct != null) RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderTexture(0, previousTexture);
            RenderSystem.enableDepthTest();
        }
    }

    @Override public boolean mouseClicked(double x, double y, int button) { session.mouseButton(x, y, button, true); return true; }
    @Override public boolean mouseReleased(double x, double y, int button) { session.mouseButton(x, y, button, false); return true; }
    @Override public void mouseMoved(double x, double y) { session.mouseMove(x, y); }
    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) { session.mouseScroll(x, y, scrollX, scrollY); return true; }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        session.key(keyCode, scanCode, modifiers, true); return true;
    }
    @Override public boolean keyReleased(int keyCode, int scanCode, int modifiers) { session.key(keyCode, scanCode, modifiers, false); return true; }
    @Override public boolean charTyped(char codePoint, int modifiers) { session.text(String.valueOf(codePoint), false, true); return true; }

    @Override public void onClose() {
        session.close();
        super.onClose();
    }
}
