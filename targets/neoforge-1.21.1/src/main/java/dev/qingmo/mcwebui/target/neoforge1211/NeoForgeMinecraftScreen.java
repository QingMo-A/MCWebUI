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
    private final boolean retainSession;

    public NeoForgeMinecraftScreen(BridgeDispatcher dispatcher, NeoForgeDemoBridge demo) {
        super(Component.literal("MCWebUI Runtime Demo"));
        this.session = new NeoForgeWebSession(dispatcher, demo);
        this.retainSession = false;
    }

    NeoForgeMinecraftScreen(NeoForgeWebSession session) {
        super(Component.literal("MCWebUI Runtime Demo"));
        this.session = session;
        this.retainSession = true;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        session.init(width, height, minecraft.getWindow().getGuiScale());
    }

    @Override public void resize(Minecraft minecraft, int width, int height) {
        if (width < 1 || height < 1) return;
        super.resize(minecraft, width, height);
        session.refreshRenderContext();
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
        if (!surface.beginRenderFrame()) return;
        try {
            renderAcquiredSurface(graphics, surface);
        } finally {
            // The native WGL lease must be released even if texture lookup, state
            // setup, buffer construction, upload, or the shader draw throws.
            surface.endRenderFrame();
        }
    }

    private void renderAcquiredSurface(GuiGraphics graphics, NeoForgeRenderableSurface surface) {
        int textureId = surface.textureId();
        // MCEF exposes texture id 0 until its render-thread initialization has completed;
        // a Direct lease can also become stale during context recreation. Returning here is
        // safe because the outer render method owns the single endRenderFrame() finally.
        if (textureId <= 0) return;
        int previousTexture = RenderSystem.getShaderTexture(0);
        SurfaceAlphaMode alphaMode = surface.alphaMode();
        try {
            RenderSystem.disableDepthTest();
            alphaMode.apply();
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, textureId);
            Tesselator tesselator = Tesselator.getInstance();
            BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            var pose = graphics.pose().last().pose();
            boolean flip = surface.yFlipped();
            buffer.addVertex(pose, 0, 0, 0).setUv(0, flip ? 1 : 0);
            buffer.addVertex(pose, 0, height, 0).setUv(0, flip ? 0 : 1);
            buffer.addVertex(pose, width, height, 0).setUv(1, flip ? 0 : 1);
            buffer.addVertex(pose, width, 0, 0).setUv(1, flip ? 1 : 0);
            BufferUploader.drawWithShader(buffer.buildOrThrow());
            surface.markFrameDrawn();
        } finally {
            try {
                alphaMode.restore();
            } finally {
                try {
                    RenderSystem.setShaderTexture(0, previousTexture);
                } finally {
                    RenderSystem.enableDepthTest();
                }
            }
        }
    }

    @Override public boolean mouseClicked(double x, double y, int button) { session.mouseButton(x, y, button, true); return true; }
    @Override public boolean mouseReleased(double x, double y, int button) { session.mouseButton(x, y, button, false); return true; }
    @Override public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) {
        // Screen's default handler does not forward drag motion to the browser. The native
        // backend keeps the pressed-button mask from mouseClicked until mouseReleased, so a
        // normal move here becomes a CEF drag and HTML range/scrollbar controls stay usable.
        session.mouseMove(x, y);
        return true;
    }
    @Override public void mouseMoved(double x, double y) { session.mouseMove(x, y); }
    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) { session.mouseScroll(x, y, scrollX, scrollY); return true; }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        session.key(keyCode, scanCode, modifiers, true); return true;
    }
    @Override public boolean keyReleased(int keyCode, int scanCode, int modifiers) { session.key(keyCode, scanCode, modifiers, false); return true; }
    @Override public boolean charTyped(char codePoint, int modifiers) { session.text(String.valueOf(codePoint), false, true); return true; }

    @Override public void onClose() {
        if (retainSession) session.deactivate();
        else session.close();
        super.onClose();
    }

    @Override public void removed() {
        if (retainSession) session.deactivate();
        else if (!session.isClosed()) session.close();
        super.removed();
    }
}
