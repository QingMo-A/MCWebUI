package dev.qingmo.mcwebui.backend;

public record DirtyRect(int x, int y, int width, int height) {
    public DirtyRect {
        if (x < 0 || y < 0 || width < 0 || height < 0) throw new IllegalArgumentException("invalid dirty rectangle");
    }
}
