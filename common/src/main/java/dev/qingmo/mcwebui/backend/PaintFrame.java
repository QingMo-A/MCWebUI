package dev.qingmo.mcwebui.backend;

import java.util.List;

public record PaintFrame(int width, int height, byte[] pixels, List<DirtyRect> dirtyRects) {
    public PaintFrame {
        if (width < 1 || height < 1) throw new IllegalArgumentException("surface dimensions must be positive");
        if (pixels == null || pixels.length < width * height * 4L) throw new IllegalArgumentException("RGBA pixels are incomplete");
        pixels = pixels.clone();
        dirtyRects = dirtyRects == null ? List.of(new DirtyRect(0, 0, width, height)) : List.copyOf(dirtyRects);
    }
    @Override public byte[] pixels() { return pixels.clone(); }
}
