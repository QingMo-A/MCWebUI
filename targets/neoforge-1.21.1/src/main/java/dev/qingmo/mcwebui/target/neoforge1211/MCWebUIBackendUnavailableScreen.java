package dev.qingmo.mcwebui.target.neoforge1211;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;

import java.nio.file.Path;
import java.util.Objects;

/** Plain Minecraft recovery UI that remains usable when no browser can start. */
final class MCWebUIBackendUnavailableScreen extends Screen {
    private final Screen previous;
    private final Runnable retry;
    private final String selectedBackend;
    private final String reason;

    MCWebUIBackendUnavailableScreen(Screen previous, String selectedBackend, String reason, Runnable retry) {
        super(Component.literal("MCWebUI Browser Backend Unavailable"));
        this.previous = previous;
        this.selectedBackend = Objects.requireNonNull(selectedBackend, "selectedBackend");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.retry = Objects.requireNonNull(retry, "retry");
    }

    @Override protected void init() {
        int y = height / 2 + 45;
        addRenderableWidget(Button.builder(Component.literal("Retry"), button -> retry.run())
                .bounds(width / 2 - 155, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Open config folder"), button -> openConfigFolder())
                .bounds(width / 2 - 50, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(width / 2 + 105, y, 100, 20).build());
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int center = width / 2;
        graphics.drawCenteredString(font, title, center, height / 2 - 70, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.literal("Selected backend: " + selectedBackend),
                center, height / 2 - 40, 0xAAAAAA);
        graphics.drawCenteredString(font, Component.literal(reason), center, height / 2 - 18, 0xFF7777);
        graphics.drawCenteredString(font,
                Component.literal("Change browserBackend in config/mcwebui-client.toml, then Retry."),
                center, height / 2 + 8, 0xAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void openConfigFolder() {
        Path folder = NeoForgeWebSession.directCefInstanceRoot().resolve("config");
        try { Util.getPlatform().openFile(folder.toFile()); }
        catch (RuntimeException ignored) { }
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(previous); }
    @Override public boolean isPauseScreen() { return false; }
}
