package dev.qingmo.mcwebui.backend;

import java.util.concurrent.atomic.AtomicLong;

/** Lightweight instrumentation for browser paint and estimated full-frame work. */
public final class FrameMetrics {
    private final AtomicLong paintCallbacks = new AtomicLong();
    private final AtomicLong estimatedPaintBytes = new AtomicLong();
    private volatile int width;
    private volatile int height;

    public void recordPaint(int width, int height) {
        this.width = width;
        this.height = height;
        paintCallbacks.incrementAndGet();
        if (width > 0 && height > 0) estimatedPaintBytes.addAndGet((long) width * height * 4L);
    }

    public long paintCallbacks() { return paintCallbacks.get(); }
    /** Estimated bytes if each paint were represented by one full RGBA frame; not GPU upload telemetry. */
    public long estimatedPaintBytes() { return estimatedPaintBytes.get(); }
    public int width() { return width; }
    public int height() { return height; }
}
